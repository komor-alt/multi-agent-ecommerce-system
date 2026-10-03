package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.AfterSalesConnector;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.ecommerce.config.AfterSalesExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final String LEASE_EXPIRED = "EXECUTION_LEASE_EXPIRED";
    private static final String EXECUTOR_SATURATED = "EXECUTION_EXECUTOR_SATURATED";

    private final ExecutionJobRepository executionJobRepository;
    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesConnector connector;
    private final ExecutionPreconditionGate preconditionGate;
    private final AfterSalesRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final AfterSalesExecutionProperties properties;
    private final Executor executionExecutor;
    private final ExecutionApprovalValidator approvalValidator;

    public ExecutionService(
            ExecutionJobRepository executionJobRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesConnector connector,
            ExecutionPreconditionGate preconditionGate,
            AfterSalesRunEventService eventService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            AfterSalesExecutionProperties properties,
            @Qualifier("afterSalesExecutionExecutor") Executor executionExecutor,
            ExecutionApprovalValidator approvalValidator) {
        this.executionJobRepository = executionJobRepository;
        this.ticketRepository = ticketRepository;
        this.connector = connector;
        this.preconditionGate = preconditionGate;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.executionExecutor = executionExecutor;
        this.approvalValidator = approvalValidator;
    }

    /**
     * 先在数据库事务中抢占任务并写入租约，再投递工作线程。多个实例扫描到同一任务时，
     * 只有拿到行锁且完成 PENDING/RETRY_WAIT -> RUNNING 转换的实例能够提交副作用。
     */
    public void executeAsync(String jobId) {
        ExecutionContext context = claim(jobId);
        if (context == null) {
            return;
        }
        try {
            executionExecutor.execute(() -> executeClaimed(context));
        } catch (RejectedExecutionException error) {
            releaseRejectedSubmission(context);
        }
    }

    /**
     * 先恢复租约过期任务，再按配置批量投递 PENDING 和到期 RETRY_WAIT。
     * 查询只是候选发现，真正的排他所有权由 claim() 的数据库行锁保证。
     */
    @Scheduled(fixedDelayString = "${agent.aftersales.execution.retry-scan-ms:5000}")
    public void retryDueJobs() {
        Instant now = Instant.now();
        recoverExpiredLeases(now);

        int batchSize = properties.getRetryBatchSize();
        List<ExecutionJobEntity> due = new ArrayList<>(executionJobRepository.findDispatchable(
                AfterSalesTypes.ExecutionStatus.PENDING, now, PageRequest.of(0, batchSize)));
        int remaining = batchSize - due.size();
        if (remaining > 0) {
            due.addAll(executionJobRepository.findDispatchable(
                    AfterSalesTypes.ExecutionStatus.RETRY_WAIT, now, PageRequest.of(0, remaining)));
        }
        due.forEach(job -> executeAsync(job.getId()));
    }

    public void retry(String jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("EXECUTION_JOB_NOT_FOUND"));
            if (job.getStatus() != AfterSalesTypes.ExecutionStatus.RETRY_WAIT
                    && job.getStatus() != AfterSalesTypes.ExecutionStatus.DEAD_LETTER) {
                throw new IllegalStateException("EXECUTION_JOB_NOT_RETRYABLE");
            }
            job.setStatus(AfterSalesTypes.ExecutionStatus.PENDING);
            job.setNextRetryAt(null);
            job.setLastError(null);
            clearLease(job);
            executionJobRepository.save(job);
        });
        executeAsync(jobId);
    }

    private ExecutionContext claim(String jobId) {
        return transactionTemplate.execute(status -> {
            ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("EXECUTION_JOB_NOT_FOUND"));
            if (job.getStatus() != AfterSalesTypes.ExecutionStatus.PENDING
                    && job.getStatus() != AfterSalesTypes.ExecutionStatus.RETRY_WAIT) {
                return null;
            }
            Instant now = Instant.now();
            if (job.getNextRetryAt() != null && job.getNextRetryAt().isAfter(now)) {
                return null;
            }
            job.setStatus(AfterSalesTypes.ExecutionStatus.RUNNING);
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLeaseOwner(properties.getWorkerId());
            job.setLeaseUntil(now.plusMillis(properties.getLeaseDurationMs()));
            executionJobRepository.save(job);
            AfterSalesTicketEntity ticket = ticketRepository.findById(job.getTicketId()).orElseThrow();
            return new ExecutionContext(job.getId(), job.getTicketId(), ticket.getCurrentRunId(),
                    ticket.getOrderId(), job.getIdempotencyKey(), job.getAmount(), job.getCurrency(),
                    job.getAttemptCount(), properties.getWorkerId(), job.getActionType(), job.getApprovalId());
        });
    }

    private void executeClaimed(ExecutionContext context) {
        try {
            eventService.append(context.runId(), "execution_started", "执行售后补偿", "running",
                    "审批已通过，服务端正在执行幂等补偿命令。", Map.of(
                            "summary", "正在执行已审批的售后命令。",
                            "jobId", context.jobId(),
                            "executionJobId", context.jobId(),
                            "approvalId", context.approvalId() == null ? "" : context.approvalId(),
                            "attempt", context.attempt()
                    ));

            AfterSalesConnector.ExecutionCommand command = new AfterSalesConnector.ExecutionCommand(
                    context.idempotencyKey(), context.orderId(), context.actionType(), context.amount(), context.currency());
            // A lost response is reconciled before checking mutable order state: an already committed
            // effect must be acknowledged even if the order has since been refunded.
            AfterSalesTypes.ExecutionResult result = connector.findExecution(command).orElse(null);
            if (result == null) {
                AfterSalesTypes.OrderSnapshot order = connector.getOrder(context.orderId());
                ExecutionPreconditionGate.ValidationResult validation = preconditionGate.validate(order);
                if (!validation.allowed()) {
                    cancelForStaleBusinessState(context, validation.reasonCode());
                    return;
                }
                String approvalRejection = transactionTemplate.execute(status -> {
                    ExecutionJobEntity current = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
                    if (!ownsAttempt(current, context) || current.getLeaseUntil() == null
                            || !current.getLeaseUntil().isAfter(Instant.now())) return "EXECUTION_LEASE_LOST";
                    AfterSalesTicketEntity ticket = ticketRepository.findById(context.ticketId()).orElseThrow();
                    return approvalValidator.rejection(current, ticket, order);
                });
                if (approvalRejection != null) {
                    cancelForStaleBusinessState(context, approvalRejection);
                    return;
                }
                result = connector.execute(order, command);
            }
            String resultJson = objectMapper.writeValueAsString(result);

            Boolean committed = transactionTemplate.execute(status -> {
                ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
                if (!ownsAttempt(job, context)) {
                    return false;
                }
                job.setStatus(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
                job.setResultPayload(resultJson);
                job.setLastError(null);
                job.setNextRetryAt(null);
                clearLease(job);
                executionJobRepository.save(job);

                AfterSalesTicketEntity ticket = ticketRepository.findById(context.ticketId()).orElseThrow();
                ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
                ticketRepository.save(ticket);
                return true;
            });
            if (!Boolean.TRUE.equals(committed)) {
                return;
            }

            eventService.append(context.runId(), "execution_completed", "补偿执行成功", "success",
                    "已审批的售后命令已执行，结果已写入审计链路。", Map.of(
                            "summary", "补偿执行成功，外部引用号 " + result.externalReference() + "。",
                            "jobId", context.jobId(),
                            "externalReference", result.externalReference(),
                            "amount", context.amount(),
                            "currency", context.currency()
                    ));
            eventService.complete(context.runId());

        } catch (Exception error) {
            boolean terminal = error instanceof com.ecommerce.aftersales.connector.ConnectorException ce
                    && ce.category() == com.ecommerce.aftersales.connector.ConnectorException.Category.TERMINAL;
            AfterSalesTypes.ExecutionStatus nextStatus = terminal || context.attempt() >= properties.getMaxAttempts()
                    ? AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                    : AfterSalesTypes.ExecutionStatus.RETRY_WAIT;
            Boolean committed = transactionTemplate.execute(status -> {
                ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
                if (!ownsAttempt(job, context)) {
                    return false;
                }
                job.setStatus(nextStatus);
                job.setLastError("CONNECTOR_EXECUTION_FAILED");
                job.setNextRetryAt(nextStatus == AfterSalesTypes.ExecutionStatus.RETRY_WAIT
                        ? Instant.now().plusMillis(properties.getRetryDelayMs())
                        : null);
                clearLease(job);
                executionJobRepository.save(job);
                return true;
            });
            if (!Boolean.TRUE.equals(committed)) {
                return;
            }
            eventService.append(context.runId(), "execution_failed", "补偿执行失败", "failed",
                    "CONNECTOR_EXECUTION_FAILED", Map.of(
                            "summary", nextStatus == AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                                    ? "执行超过最大重试次数，已进入死信状态。"
                                    : "执行失败，已进入重试时间窗。",
                            "jobId", context.jobId(),
                            "status", nextStatus.name(),
                            "attempt", context.attempt()
                    ));
        }
    }

    private void cancelForStaleBusinessState(ExecutionContext context, String reasonCode) {
        Boolean cancelled = transactionTemplate.execute(status -> {
            ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
            if (!ownsAttempt(job, context)) {
                return false;
            }
            job.setStatus(AfterSalesTypes.ExecutionStatus.CANCELLED);
            job.setLastError(reasonCode);
            job.setNextRetryAt(null);
            clearLease(job);
            executionJobRepository.save(job);

            AfterSalesTicketEntity ticket = ticketRepository.findById(context.ticketId()).orElseThrow();
            ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
            ticketRepository.save(ticket);
            return true;
        });
        if (!Boolean.TRUE.equals(cancelled)) {
            return;
        }

        eventService.append(context.runId(), "execution_cancelled", "补偿执行已取消", "warning",
                reasonCode, Map.of(
                        "summary", "订单状态已变化，执行前校验未通过，补偿任务已终止。",
                        "jobId", context.jobId(),
                        "reasonCode", reasonCode,
                        "guardCode", reasonCode
                ));
        eventService.complete(context.runId());
    }

    /**
     * 线程池拒绝发生在数据库抢占之后：把本次未实际执行的 attempt 退回，并设置下一次
     * 投递时间。任务不会因进程内队列饱和而丢失。
     */
    private void releaseRejectedSubmission(ExecutionContext context) {
        Boolean released = transactionTemplate.execute(status -> {
            ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
            if (!ownsAttempt(job, context)) {
                return false;
            }
            job.setStatus(AfterSalesTypes.ExecutionStatus.RETRY_WAIT);
            job.setAttemptCount(Math.max(0, job.getAttemptCount() - 1));
            job.setNextRetryAt(Instant.now().plusMillis(properties.getRetryDelayMs()));
            job.setLastError(EXECUTOR_SATURATED);
            clearLease(job);
            executionJobRepository.save(job);
            return true;
        });
        if (Boolean.TRUE.equals(released)) {
            log.warn("After-sales execution deferred because executor is saturated (jobId={})", context.jobId());
        }
    }

    private void recoverExpiredLeases(Instant now) {
        List<ExecutionJobEntity> expired = executionJobRepository.findExpiredLeases(
                AfterSalesTypes.ExecutionStatus.RUNNING,
                now,
                PageRequest.of(0, properties.getRetryBatchSize()));
        for (ExecutionJobEntity candidate : expired) {
            LeaseRecovery recovery = transactionTemplate.execute(status -> {
                ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(candidate.getId()).orElseThrow();
                if (job.getStatus() != AfterSalesTypes.ExecutionStatus.RUNNING
                        || job.getLeaseUntil() == null
                        || job.getLeaseUntil().isAfter(now)) {
                    return null;
                }
                AfterSalesTypes.ExecutionStatus nextStatus =
                        job.getAttemptCount() >= properties.getMaxAttempts()
                                ? AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                                : AfterSalesTypes.ExecutionStatus.RETRY_WAIT;
                job.setStatus(nextStatus);
                job.setNextRetryAt(nextStatus == AfterSalesTypes.ExecutionStatus.RETRY_WAIT ? now : null);
                job.setLastError(LEASE_EXPIRED);
                clearLease(job);
                executionJobRepository.save(job);
                AfterSalesTicketEntity ticket = ticketRepository.findById(job.getTicketId()).orElseThrow();
                return new LeaseRecovery(job.getId(), ticket.getCurrentRunId(), nextStatus);
            });
            if (recovery != null) {
                eventService.append(recovery.runId(), "execution_lease_recovered",
                        "执行任务租约已恢复", "warning", LEASE_EXPIRED, Map.of(
                                "summary", recovery.status() == AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                                        ? "执行任务租约过期且已达到最大尝试次数，任务进入死信。"
                                        : "执行任务租约过期，任务已重新进入持久化重试队列。",
                                "jobId", recovery.jobId(),
                                "status", recovery.status().name()
                        ));
            }
        }
    }

    private static boolean ownsAttempt(ExecutionJobEntity job, ExecutionContext context) {
        return job.getStatus() == AfterSalesTypes.ExecutionStatus.RUNNING
                && job.getAttemptCount() == context.attempt()
                && context.leaseOwner().equals(job.getLeaseOwner());
    }

    private static void clearLease(ExecutionJobEntity job) {
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
    }

    private record ExecutionContext(
            String jobId,
            String ticketId,
            String runId,
            String orderId,
            String idempotencyKey,
            java.math.BigDecimal amount,
            String currency,
            int attempt,
            String leaseOwner,
            String actionType,
            String approvalId
    ) {
    }

    private record LeaseRecovery(
            String jobId,
            String runId,
            AfterSalesTypes.ExecutionStatus status
    ) {
    }
}

