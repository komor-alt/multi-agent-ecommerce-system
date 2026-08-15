package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.*;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.*;
import com.ecommerce.data.DemoFulfillmentDataFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class AfterSalesService {
    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesRunRepository runRepository;
    private final ActionProposalRepository proposalRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ExecutionJobRepository executionJobRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final AfterSalesAgentLoopService agentLoopService;
    private final AfterSalesRunEventService eventService;
    private final ApprovalService approvalService;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final Executor agentExecutor;
    /** Same-process idempotency guard for concurrent analyze POSTs on one ticket. */
    private final ConcurrentHashMap<String, Object> analyzeLocks = new ConcurrentHashMap<>();

    /** 已知演示订单 → 国家。与 MockShopifyAfterSalesConnector 共用同一份订单数据，未知订单返回 null（前端展示「未知」）。 */
    private static final Map<String, String> KNOWN_ORDER_COUNTRIES = DemoFulfillmentDataFactory.createOrders().stream()
            .collect(Collectors.toMap(
                    order -> String.valueOf(order.get("order_id")),
                    order -> String.valueOf(order.get("country"))));

    /** 客户消息 API 允许的附件元数据键：只允许文件事实，核验结论（reviewStatus 等）是服务端持有键。 */
    private static final Set<String> ALLOWED_ATTACHMENT_METADATA_KEYS = Set.of(
            TicketAttachmentEntity.METADATA_WIDTH,
            TicketAttachmentEntity.METADATA_HEIGHT,
            TicketAttachmentEntity.METADATA_SIZE_BYTES);

    public AfterSalesService(
            AfterSalesTicketRepository ticketRepository,
            AfterSalesRunRepository runRepository,
            ActionProposalRepository proposalRepository,
            ApprovalRecordRepository approvalRecordRepository,
            ExecutionJobRepository executionJobRepository,
            TicketMessageRepository messageRepository,
            TicketAttachmentRepository attachmentRepository,
            AfterSalesAgentLoopService agentLoopService,
            AfterSalesRunEventService eventService,
            ApprovalService approvalService,
            ExecutionService executionService,
            ObjectMapper objectMapper,
            @Qualifier("agentExecutor") Executor agentExecutor) {
        this.ticketRepository = ticketRepository;
        this.runRepository = runRepository;
        this.proposalRepository = proposalRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.executionJobRepository = executionJobRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.agentLoopService = agentLoopService;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<AfterSalesTicketEntity> tickets = ticketRepository.findTop20ByOrderByCreatedAtDesc();
        // 一次批量查询所有工单的最新执行任务，避免逐工单 N+1；同一工单多任务时取 updatedAt 最新者。
        // 空列表时不发起 findByTicketIdIn(empty)：避免无意义的 IN () 查询。
        Map<String, ExecutionJobEntity> latestJobByTicketId = tickets.isEmpty()
                ? Map.of()
                : executionJobRepository
                .findByTicketIdIn(tickets.stream().map(AfterSalesTicketEntity::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        ExecutionJobEntity::getTicketId,
                        job -> job,
                        (first, second) -> first.getUpdatedAt() == null ? second
                                : second.getUpdatedAt() == null ? first
                                : second.getUpdatedAt().isAfter(first.getUpdatedAt()) ? second : first));
        return tickets.stream()
                .map(ticket -> ticketSummary(ticket, latestJobByTicketId.get(ticket.getId())))
                .toList();
    }

    @Transactional
    public Map<String, Object> create(String orderId, String customerMessage) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("ORDER_ID_REQUIRED");
        }
        if (customerMessage == null || customerMessage.isBlank()) {
            throw new IllegalArgumentException("CUSTOMER_MESSAGE_REQUIRED");
        }
        String id = UUID.randomUUID().toString();
        String cleanMessage = customerMessage.trim();
        AfterSalesTicketEntity ticket = ticketRepository.save(AfterSalesTicketEntity.builder()
                .id(id)
                .ticketNo("AS-" + Instant.now().toEpochMilli())
                .orderId(orderId.trim())
                .issueType("SHIPMENT_DELAY")
                .customerMessage(cleanMessage)
                .status(AfterSalesTypes.TicketStatus.OPEN)
                .build());
        // 工单创建时持久化初始客户消息（时间线第一条消息，属于客户）。
        messageRepository.save(TicketMessageEntity.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(id)
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content(cleanMessage)
                .createdAt(Instant.now())
                .build());
        return ticketSummary(ticket);
    }

    @Transactional
    public Map<String, Object> analyze(String ticketId, boolean deferred) {
        Object lock = analyzeLocks.computeIfAbsent(ticketId, ignored -> new Object());
        synchronized (lock) {
            return analyzeLocked(ticketId, deferred);
        }
    }

    private Map<String, Object> analyzeLocked(String ticketId, boolean deferred) {
        AfterSalesTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("TICKET_NOT_FOUND"));
        if (ticket.getStatus() == AfterSalesTypes.TicketStatus.ANALYZING) {
            return Map.of(
                    "ticketId", ticketId,
                    "runId", ticket.getCurrentRunId(),
                    "status", "ANALYZING",
                    "streamUrl", "/api/v1/after-sales/runs/" + ticket.getCurrentRunId() + "/stream"
            );
        }
        if (ticket.getStatus() != AfterSalesTypes.TicketStatus.OPEN
                && ticket.getStatus() != AfterSalesTypes.TicketStatus.FAILED) {
            throw new IllegalStateException("TICKET_NOT_ANALYZABLE");
        }

        String runId = UUID.randomUUID().toString();
        String runStatus = deferred ? "READY" : "RUNNING";
        // 预算 8：SHIPMENT_DELAY 图（3 取证 + 2 决策 = 5）与 LOST_IN_TRANSIT 图（4 + 2 = 6）
        // 都在预算内，DAMAGED_ITEM 图（5 取证 + 2 决策 = 7）恰好可完成。
        runRepository.save(AfterSalesRunEntity.builder()
                .id(runId)
                .ticketId(ticketId)
                .status(runStatus)
                .maxSteps(8)
                .stepCount(0)
                .startedAt(deferred ? null : Instant.now())
                .build());
        ticket.setStatus(AfterSalesTypes.TicketStatus.ANALYZING);
        ticket.setCurrentRunId(runId);
        ticketRepository.save(ticket);

        if (!deferred) {
            // 立即模式：两个 save 各自提交后才异步启动，Agent 线程一定能读到已提交的 run。
            CompletableFuture.runAsync(() -> agentLoopService.run(runId, ticketId), agentExecutor);
        }
        return Map.of(
                "ticketId", ticketId,
                "runId", runId,
                "status", "ANALYZING",
                "runStatus", runStatus,
                "streamUrl", "/api/v1/after-sales/runs/" + runId + "/stream"
        );
    }

    /**
     * 客户补充信息（消息 + 附件元数据）：仅 WAITING_CUSTOMER 工单可恢复，其余状态一律拒绝
     * （TICKET_NOT_RESUMABLE）。持久化客户消息与附件元数据后，创建关联父 run 的新 run
     * （parentRunId = 等待状态会话），恢复取证：deferred=true → READY（先订阅后 start），
     * 否则 RUNNING 并立即异步启动。附件的二进制内容永远不落库，只存元数据。
     */
    @Transactional
    public Map<String, Object> appendCustomerMessage(
            String ticketId,
            String content,
            List<AttachmentInput> attachments,
            boolean deferred) {
        AfterSalesTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("TICKET_NOT_FOUND"));
        if (ticket.getStatus() != AfterSalesTypes.TicketStatus.WAITING_CUSTOMER) {
            throw new IllegalStateException("TICKET_NOT_RESUMABLE");
        }
        String cleanContent = content == null ? "" : content.trim();
        List<AttachmentInput> cleanAttachments = attachments == null ? List.of() : attachments;
        if (cleanContent.isBlank() && cleanAttachments.isEmpty()) {
            throw new IllegalArgumentException("MESSAGE_OR_ATTACHMENT_REQUIRED");
        }
        cleanAttachments.forEach(this::validateAttachment);
        // 不安全恢复拒绝：父 run 必须存在且携带可恢复会话快照（stopReason CUSTOMER_INFO_REQUIRED）。
        AfterSalesRunEntity parentRun = runRepository.findById(ticket.getCurrentRunId())
                .orElseThrow(() -> new IllegalStateException("PARENT_RUN_NOT_FOUND"));
        if (!"CUSTOMER_INFO_REQUIRED".equals(parentRun.getStopReason())
                || parentRun.getResumeStateJson() == null
                || parentRun.getResumeStateJson().isBlank()) {
            throw new IllegalStateException("PARENT_RUN_NOT_RESUMABLE");
        }

        TicketMessageEntity message = messageRepository.save(TicketMessageEntity.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(ticketId)
                .runId(parentRun.getId())
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content(cleanContent.isBlank() ? "（仅上传附件）" : cleanContent)
                .createdAt(Instant.now())
                .build());
        Instant now = Instant.now();
        for (AttachmentInput input : cleanAttachments) {
            attachmentRepository.save(TicketAttachmentEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .ticketId(ticketId)
                    .messageId(message.getId())
                    .fileName(input.fileName().trim())
                    .contentType(input.contentType().trim())
                    .url(blankToNull(input.url()))
                    .storageKey(blankToNull(input.storageKey()))
                    .checksum(blankToNull(input.checksum()))
                    .metadataJson(writeMetadataJson(input.metadata()))
                    .createdAt(now)
                    .build());
        }

        String runId = UUID.randomUUID().toString();
        String runStatus = deferred ? "READY" : "RUNNING";
        runRepository.save(AfterSalesRunEntity.builder()
                .id(runId)
                .ticketId(ticketId)
                .parentRunId(parentRun.getId())
                .status(runStatus)
                .maxSteps(8)
                .stepCount(0)
                .startedAt(deferred ? null : Instant.now())
                .build());
        ticket.setStatus(AfterSalesTypes.TicketStatus.ANALYZING);
        ticket.setCurrentRunId(runId);
        ticketRepository.save(ticket);

        if (!deferred) {
            // 立即模式：两个 save 各自提交后才异步启动，Agent 线程一定能读到已提交的 run。
            CompletableFuture.runAsync(() -> agentLoopService.run(runId, ticketId), agentExecutor);
        }
        return Map.of(
                "ticketId", ticketId,
                "runId", runId,
                "status", "ANALYZING",
                "runStatus", runStatus,
                "streamUrl", "/api/v1/after-sales/runs/" + runId + "/stream"
        );
    }

    /** 附件输入校验（失败关闭）：文件名/类型必填且类型必须为 image/*（破损照片证据只接受图片），
     *  url 与 storageKey 至少其一；元数据只允许文件事实键且值必须标量。 */
    private void validateAttachment(AttachmentInput input) {
        if (input == null
                || !StringUtils.hasText(input.fileName())
                || !StringUtils.hasText(input.contentType())) {
            throw new IllegalArgumentException("ATTACHMENT_INVALID");
        }
        if (!input.contentType().trim().toLowerCase().startsWith("image/")) {
            throw new IllegalArgumentException("ATTACHMENT_CONTENT_TYPE_NOT_IMAGE");
        }
        if (!StringUtils.hasText(input.url()) && !StringUtils.hasText(input.storageKey())) {
            throw new IllegalArgumentException("ATTACHMENT_INVALID");
        }
        if (input.metadata() != null) {
            for (Map.Entry<String, Object> entry : input.metadata().entrySet()) {
                if (!ALLOWED_ATTACHMENT_METADATA_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("ATTACHMENT_METADATA_KEY_NOT_ALLOWED");
                }
                Object value = entry.getValue();
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw new IllegalArgumentException("ATTACHMENT_METADATA_VALUE_INVALID");
                }
            }
        }
    }

    private String writeMetadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception error) {
            throw new IllegalStateException("ATTACHMENT_METADATA_SERIALIZATION_FAILED", error);
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 客户消息 API 的附件元数据输入（只存元数据，不存二进制；fileName/contentType/url 或 storageKey 必填）。 */
    public record AttachmentInput(
            String fileName,
            String contentType,
            String url,
            String storageKey,
            String checksum,
            Map<String, Object> metadata) {
    }

    /**
     * 「先订阅、后启动」的启动入口：原子认领 READY -> RUNNING（startedAt 在认领时写入），
     * 认领事务提交后才把 run 提交给 agentExecutor，异步线程读到的一定是已提交的 RUNNING。
     * 并发或重复调用最多启动一次；READY（尚未认领到）/RUNNING/COMPLETED/FAILED 均返回结构化状态。
     */
    public Map<String, Object> start(String runId) {
        int claimed = runRepository.claimReady(runId, Instant.now());
        if (claimed == 1) {
            AfterSalesRunEntity run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalArgumentException("AGENT_RUN_NOT_FOUND"));
            CompletableFuture.runAsync(() -> agentLoopService.run(runId, run.getTicketId()), agentExecutor);
        }
        return runStatus(runId);
    }

    private Map<String, Object> runStatus(String runId) {
        AfterSalesRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("AGENT_RUN_NOT_FOUND"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("ticketId", run.getTicketId());
        result.put("status", run.getStatus());
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("durationMs", run.getDurationMs());
        result.put("stopReason", run.getStopReason());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String ticketId) {
        AfterSalesTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("TICKET_NOT_FOUND"));
        Map<String, Object> result = new LinkedHashMap<>(ticketSummary(ticket));
        proposalRepository.findTopByTicketIdOrderByCreatedAtDesc(ticketId).ifPresent(proposal -> {
            result.put("proposal", proposalMap(proposal));
            result.put("approvals", approvalRecordRepository.findByProposalIdOrderByCreatedAtAsc(proposal.getId()).stream()
                    .map(this::approvalMap)
                    .toList());
            executionJobRepository.findByProposalId(proposal.getId())
                    .ifPresent(job -> result.put("executionJob", executionMap(job)));
        });
        if (ticket.getCurrentRunId() != null) {
            runRepository.findById(ticket.getCurrentRunId()).ifPresent(run -> result.put("run", runMap(run)));
            result.put("events", eventService.history(ticket.getCurrentRunId()));
        }
        // 消息时间线（按时间升序）：每条消息带其附件元数据。
        List<Map<String, Object>> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::messageMap)
                .toList();
        if (!messages.isEmpty()) {
            result.put("messages", messages);
        }
        return result;
    }

    // 审批人身份由控制器从可信 Gateway Header 构建的 OperatorContext 传入（普通 body 无法影响）：
    // 本服务只读取 operator.id() 写入事件，ApprovalService 继续接收已可信的 String。
    public Map<String, Object> approve(String proposalId, OperatorContext operator, String comment) {
        String operatorId = operator.id();
        ApprovalService.ApprovalOutcome outcome = approvalService.approve(proposalId, operatorId, comment);
        ExecutionJobEntity job = outcome.job();
        if (outcome.newlyApproved()) {
            AfterSalesTicketEntity ticket = ticketRepository.findById(job.getTicketId()).orElseThrow();
            eventService.append(ticket.getCurrentRunId(), "approval_recorded", "人工审批通过", "success",
                    "运营人员批准延迟补偿方案。", Map.of(
                            "summary", "人工审批通过，已创建唯一执行任务。",
                            "proposalId", proposalId,
                            "jobId", job.getId(),
                            "operatorId", operatorId,
                            "decision", "APPROVED"
                    ));
            executionService.executeAsync(job.getId());
        }
        return executionMap(job);
    }

    public Map<String, Object> reject(String proposalId, OperatorContext operator, String comment) {
        String operatorId = operator.id();
        ActionProposalEntity proposal = approvalService.reject(proposalId, operatorId, comment);
        AfterSalesTicketEntity ticket = ticketRepository.findById(proposal.getTicketId()).orElseThrow();
        ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
        ticketRepository.save(ticket);
        eventService.append(ticket.getCurrentRunId(), "approval_recorded", "人工驳回方案", "success",
                "运营人员驳回延迟补偿方案。", Map.of(
                        "summary", "方案已驳回，不会产生任何副作用。",
                        "proposalId", proposalId,
                        "operatorId", operatorId,
                        "decision", "REJECTED"
                ));
        eventService.complete(ticket.getCurrentRunId());
        return proposalMap(proposal);
    }

    public Map<String, Object> retry(String jobId) {
        executionService.retry(jobId);
        return executionJobRepository.findById(jobId)
                .map(this::executionMap)
                .orElseThrow(() -> new IllegalArgumentException("EXECUTION_JOB_NOT_FOUND"));
    }

    private Map<String, Object> ticketSummary(AfterSalesTicketEntity ticket) {
        return ticketSummary(ticket, null);
    }

    private Map<String, Object> ticketSummary(AfterSalesTicketEntity ticket, ExecutionJobEntity latestJob) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ticket.getId());
        result.put("ticketNo", ticket.getTicketNo());
        result.put("orderId", ticket.getOrderId());
        result.put("country", KNOWN_ORDER_COUNTRIES.get(ticket.getOrderId()));
        result.put("userId", ticket.getUserId());
        result.put("issueType", ticket.getIssueType());
        result.put("customerMessage", ticket.getCustomerMessage());
        result.put("status", ticket.getStatus().name());
        result.put("runId", ticket.getCurrentRunId());
        result.put("createdAt", ticket.getCreatedAt());
        result.put("updatedAt", ticket.getUpdatedAt());
        // 可选字段：仅当存在执行任务时输出，供队列展示「执行异常」（RETRY_WAIT / DEAD_LETTER）。
        if (latestJob != null) {
            result.put("executionStatus", latestJob.getStatus().name());
        }
        // 可选字段：转人工升级（ESCALATED）或外部等待（WAITING_EXTERNAL）的结构化原因。
        if (ticket.getEscalationReasonJson() != null && !ticket.getEscalationReasonJson().isBlank()) {
            result.put("escalationReason", readMap(ticket.getEscalationReasonJson()));
        }
        return result;
    }

    private Map<String, Object> proposalMap(ActionProposalEntity proposal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", proposal.getId());
        result.put("ticketId", proposal.getTicketId());
        result.put("actionType", proposal.getActionType());
        result.put("amount", proposal.getAmount());
        result.put("currency", proposal.getCurrency());
        result.put("policyVersion", proposal.getPolicyVersion());
        result.put("proposalVersion", proposal.getProposalVersion());
        result.put("decisionSummary", proposal.getDecisionSummary());
        result.put("evidenceIds", readList(proposal.getEvidenceIdsJson()));
        result.put("status", proposal.getStatus().name());
        result.put("reviewedBy", proposal.getReviewedBy());
        result.put("reviewComment", proposal.getReviewComment());
        result.put("reviewedAt", proposal.getReviewedAt());
        return result;
    }

    private Map<String, Object> approvalMap(ApprovalRecordEntity approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", approval.getId());
        result.put("decision", approval.getDecision());
        result.put("operatorId", approval.getOperatorId());
        result.put("comment", approval.getComment());
        result.put("createdAt", approval.getCreatedAt());
        return result;
    }

    private Map<String, Object> executionMap(ExecutionJobEntity job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.getId());
        result.put("proposalId", job.getProposalId());
        result.put("actionType", job.getActionType());
        result.put("amount", job.getAmount());
        result.put("currency", job.getCurrency());
        result.put("status", job.getStatus().name());
        result.put("attemptCount", job.getAttemptCount());
        result.put("nextRetryAt", job.getNextRetryAt());
        result.put("lastError", job.getLastError());
        result.put("result", readMap(job.getResultPayload()));
        result.put("updatedAt", job.getUpdatedAt());
        return result;
    }

    private Map<String, Object> runMap(AfterSalesRunEntity run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.getId());
        result.put("status", run.getStatus());
        result.put("stepCount", run.getStepCount());
        result.put("maxSteps", run.getMaxSteps());
        result.put("stopReason", run.getStopReason());
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("durationMs", run.getDurationMs());
        result.put("finalAnswer", readMap(run.getFinalAnswerJson()));
        if (run.getParentRunId() != null) {
            result.put("parentRunId", run.getParentRunId());
        }
        return result;
    }

    private Map<String, Object> messageMap(TicketMessageEntity message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("role", message.getRole().name());
        result.put("content", message.getContent());
        result.put("createdAt", message.getCreatedAt());
        if (message.getRunId() != null) {
            result.put("runId", message.getRunId());
        }
        List<Map<String, Object>> attachments = attachmentRepository
                .findByMessageIdOrderByCreatedAtAsc(message.getId()).stream()
                .map(this::attachmentMap)
                .toList();
        if (!attachments.isEmpty()) {
            result.put("attachments", attachments);
        }
        return result;
    }

    private Map<String, Object> attachmentMap(TicketAttachmentEntity attachment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attachment.getId());
        result.put("fileName", attachment.getFileName());
        result.put("contentType", attachment.getContentType());
        result.put("url", attachment.getUrl());
        result.put("storageKey", attachment.getStorageKey());
        result.put("checksum", attachment.getChecksum());
        result.put("metadata", readMap(attachment.getMetadataJson()));
        result.put("createdAt", attachment.getCreatedAt());
        return result;
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception error) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }
}



