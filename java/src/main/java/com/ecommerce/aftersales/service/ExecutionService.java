package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionService {
    private static final int MAX_ATTEMPTS = 3;

    private final ExecutionJobRepository executionJobRepository;
    private final AfterSalesTicketRepository ticketRepository;
    private final MockShopifyAfterSalesConnector connector;
    private final AfterSalesRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ExecutionService(
            ExecutionJobRepository executionJobRepository,
            AfterSalesTicketRepository ticketRepository,
            MockShopifyAfterSalesConnector connector,
            AfterSalesRunEventService eventService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.executionJobRepository = executionJobRepository;
        this.ticketRepository = ticketRepository;
        this.connector = connector;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Async("agentExecutor")
    public void executeAsync(String jobId) {
        executeJob(jobId);
    }

    @Scheduled(fixedDelayString = "${agent.aftersales.retry-scan-ms:5000}")
    public void retryDueJobs() {
        List<ExecutionJobEntity> due = executionJobRepository
                .findTop10ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        AfterSalesTypes.ExecutionStatus.RETRY_WAIT,
                        Instant.now()
                );
        due.forEach(job -> executeJob(job.getId()));
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
            executionJobRepository.save(job);
        });
        executeAsync(jobId);
    }

    private void executeJob(String jobId) {
        ExecutionContext context = transactionTemplate.execute(status -> {
            ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("EXECUTION_JOB_NOT_FOUND"));
            if (job.getStatus() == AfterSalesTypes.ExecutionStatus.SUCCEEDED
                    || job.getStatus() == AfterSalesTypes.ExecutionStatus.RUNNING) {
                return null;
            }
            job.setStatus(AfterSalesTypes.ExecutionStatus.RUNNING);
            job.setAttemptCount(job.getAttemptCount() + 1);
            executionJobRepository.save(job);
            AfterSalesTicketEntity ticket = ticketRepository.findById(job.getTicketId()).orElseThrow();
            return new ExecutionContext(job.getId(), job.getTicketId(), ticket.getCurrentRunId(),
                    ticket.getOrderId(), job.getIdempotencyKey(), job.getAmount(), job.getCurrency(), job.getAttemptCount());
        });
        if (context == null) {
            return;
        }

        eventService.append(context.runId(), "execution_started", "执行延迟补偿", "running",
                "审批已通过，服务端正在执行幂等补偿命令。", Map.of(
                        "summary", "正在发放延迟补偿券。",
                        "jobId", context.jobId(),
                        "attempt", context.attempt()
                ));

        try {
            AfterSalesTypes.OrderSnapshot order = connector.getOrder(context.orderId());
            AfterSalesTypes.ExecutionResult result =
                    connector.issueDelayCoupon(order, context.amount(), context.idempotencyKey());
            String resultJson = objectMapper.writeValueAsString(result);

            transactionTemplate.executeWithoutResult(status -> {
                ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
                job.setStatus(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
                job.setResultPayload(resultJson);
                job.setLastError(null);
                job.setNextRetryAt(null);
                executionJobRepository.save(job);

                AfterSalesTicketEntity ticket = ticketRepository.findById(context.ticketId()).orElseThrow();
                ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
                ticketRepository.save(ticket);
            });

            eventService.append(context.runId(), "execution_completed", "补偿执行成功", "success",
                    "延迟补偿券已发放，执行结果已写入审计链路。", Map.of(
                            "summary", "补偿执行成功，外部引用号 " + result.externalReference() + "。",
                            "jobId", context.jobId(),
                            "externalReference", result.externalReference(),
                            "amount", context.amount(),
                            "currency", context.currency()
                    ));
            eventService.complete(context.runId());

        } catch (Exception error) {
            AfterSalesTypes.ExecutionStatus nextStatus = context.attempt() >= MAX_ATTEMPTS
                    ? AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                    : AfterSalesTypes.ExecutionStatus.RETRY_WAIT;
            transactionTemplate.executeWithoutResult(status -> {
                ExecutionJobEntity job = executionJobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
                job.setStatus(nextStatus);
                job.setLastError(error.getMessage());
                job.setNextRetryAt(nextStatus == AfterSalesTypes.ExecutionStatus.RETRY_WAIT
                        ? Instant.now().plus(10, ChronoUnit.SECONDS)
                        : null);
                executionJobRepository.save(job);
            });
            eventService.append(context.runId(), "execution_failed", "补偿执行失败", "failed",
                    error.getMessage(), Map.of(
                            "summary", nextStatus == AfterSalesTypes.ExecutionStatus.DEAD_LETTER
                                    ? "执行超过最大重试次数，已进入死信状态。"
                                    : "执行失败，已进入重试时间窗。",
                            "jobId", context.jobId(),
                            "status", nextStatus.name(),
                            "attempt", context.attempt()
                    ));
        }
    }

    private record ExecutionContext(
            String jobId,
            String ticketId,
            String runId,
            String orderId,
            String idempotencyKey,
            java.math.BigDecimal amount,
            String currency,
            int attempt
    ) {
    }
}

