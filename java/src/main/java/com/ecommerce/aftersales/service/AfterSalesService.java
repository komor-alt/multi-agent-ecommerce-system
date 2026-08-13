package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.*;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class AfterSalesService {
    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesRunRepository runRepository;
    private final ActionProposalRepository proposalRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ExecutionJobRepository executionJobRepository;
    private final AfterSalesAgentLoopService agentLoopService;
    private final AfterSalesRunEventService eventService;
    private final ApprovalService approvalService;
    private final ExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final Executor agentExecutor;

    public AfterSalesService(
            AfterSalesTicketRepository ticketRepository,
            AfterSalesRunRepository runRepository,
            ActionProposalRepository proposalRepository,
            ApprovalRecordRepository approvalRecordRepository,
            ExecutionJobRepository executionJobRepository,
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
        this.agentLoopService = agentLoopService;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
    }

    public List<Map<String, Object>> list() {
        return ticketRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(this::ticketSummary)
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
        AfterSalesTicketEntity ticket = ticketRepository.save(AfterSalesTicketEntity.builder()
                .id(id)
                .ticketNo("AS-" + Instant.now().toEpochMilli())
                .orderId(orderId.trim())
                .issueType("SHIPMENT_DELAY")
                .customerMessage(customerMessage.trim())
                .status(AfterSalesTypes.TicketStatus.OPEN)
                .build());
        return ticketSummary(ticket);
    }

    public Map<String, Object> analyze(String ticketId) {
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
        runRepository.save(AfterSalesRunEntity.builder()
                .id(runId)
                .ticketId(ticketId)
                .status("RUNNING")
                .maxSteps(6)
                .stepCount(0)
                .startedAt(Instant.now())
                .build());
        ticket.setStatus(AfterSalesTypes.TicketStatus.ANALYZING);
        ticket.setCurrentRunId(runId);
        ticketRepository.save(ticket);

        CompletableFuture.runAsync(() -> agentLoopService.run(runId, ticketId), agentExecutor);
        return Map.of(
                "ticketId", ticketId,
                "runId", runId,
                "status", "ANALYZING",
                "streamUrl", "/api/v1/after-sales/runs/" + runId + "/stream"
        );
    }

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
        return result;
    }

    public Map<String, Object> approve(String proposalId, String operatorId, String comment) {
        String safeOperator = operatorId == null || operatorId.isBlank() ? "operator-demo" : operatorId.trim();
        ApprovalService.ApprovalOutcome outcome = approvalService.approve(proposalId, safeOperator, comment);
        ExecutionJobEntity job = outcome.job();
        if (outcome.newlyApproved()) {
            AfterSalesTicketEntity ticket = ticketRepository.findById(job.getTicketId()).orElseThrow();
            eventService.append(ticket.getCurrentRunId(), "approval_recorded", "人工审批通过", "success",
                    "运营人员批准延迟补偿方案。", Map.of(
                            "summary", "人工审批通过，已创建唯一执行任务。",
                            "proposalId", proposalId,
                            "jobId", job.getId(),
                            "operatorId", safeOperator,
                            "decision", "APPROVED"
                    ));
            executionService.executeAsync(job.getId());
        }
        return executionMap(job);
    }

    public Map<String, Object> reject(String proposalId, String operatorId, String comment) {
        String safeOperator = operatorId == null || operatorId.isBlank() ? "operator-demo" : operatorId.trim();
        ActionProposalEntity proposal = approvalService.reject(proposalId, safeOperator, comment);
        AfterSalesTicketEntity ticket = ticketRepository.findById(proposal.getTicketId()).orElseThrow();
        ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
        ticketRepository.save(ticket);
        eventService.append(ticket.getCurrentRunId(), "approval_recorded", "人工驳回方案", "success",
                "运营人员驳回延迟补偿方案。", Map.of(
                        "summary", "方案已驳回，不会产生任何副作用。",
                        "proposalId", proposalId,
                        "operatorId", safeOperator,
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ticket.getId());
        result.put("ticketNo", ticket.getTicketNo());
        result.put("orderId", ticket.getOrderId());
        result.put("userId", ticket.getUserId());
        result.put("issueType", ticket.getIssueType());
        result.put("customerMessage", ticket.getCustomerMessage());
        result.put("status", ticket.getStatus().name());
        result.put("runId", ticket.getCurrentRunId());
        result.put("createdAt", ticket.getCreatedAt());
        result.put("updatedAt", ticket.getUpdatedAt());
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
        result.put("finalAnswer", readMap(run.getFinalAnswerJson()));
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



