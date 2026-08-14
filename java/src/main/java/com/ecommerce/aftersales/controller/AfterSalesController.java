package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.AfterSalesRunEventService;
import com.ecommerce.aftersales.service.AfterSalesService;
import com.ecommerce.aftersales.service.OperatorContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/after-sales")
public class AfterSalesController {
    private final AfterSalesService afterSalesService;
    private final AfterSalesRunEventService eventService;

    public AfterSalesController(AfterSalesService afterSalesService, AfterSalesRunEventService eventService) {
        this.afterSalesService = afterSalesService;
        this.eventService = eventService;
    }

    @GetMapping("/tickets")
    public Map<String, Object> listTickets() {
        List<Map<String, Object>> items = afterSalesService.list();
        return Map.of("items", items, "total", items.size());
    }

    @PostMapping("/tickets")
    public Map<String, Object> createTicket(@RequestBody CreateTicketRequest request) {
        return afterSalesService.create(request.orderId(), request.customerMessage());
    }

    @GetMapping("/tickets/{ticketId}")
    public Map<String, Object> getTicket(@PathVariable String ticketId) {
        return afterSalesService.detail(ticketId);
    }

    @PostMapping("/tickets/{ticketId}/analyze")
    public Map<String, Object> analyze(
            @PathVariable String ticketId,
            @RequestParam(value = "deferred", required = false, defaultValue = "false") boolean deferred) {
        return afterSalesService.analyze(ticketId, deferred);
    }

    @PostMapping("/runs/{runId}/start")
    public Map<String, Object> startRun(@PathVariable String runId) {
        return afterSalesService.start(runId);
    }

    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "lastEventId", required = false) String queryLastEventId) {
        return eventService.stream(runId, lastEventId != null ? lastEventId : queryLastEventId);
    }

    // 审批人身份只来自可信 Gateway Header（X-Authenticated-Operator），绝不接受 body 中的 operatorId：
    // ReviewRequest 只有 comment，缺失/非法 Header 由 OperatorContext 抛结构化安全异常（401/400）。
    @PostMapping("/proposals/{proposalId}/approve")
    public Map<String, Object> approve(
            @PathVariable String proposalId,
            @RequestHeader(value = OperatorContext.HEADER_NAME, required = false) String authenticatedOperator,
            @RequestBody ReviewRequest request) {
        return afterSalesService.approve(proposalId,
                OperatorContext.fromTrustedGatewayHeader(authenticatedOperator), request.comment());
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public Map<String, Object> reject(
            @PathVariable String proposalId,
            @RequestHeader(value = OperatorContext.HEADER_NAME, required = false) String authenticatedOperator,
            @RequestBody ReviewRequest request) {
        return afterSalesService.reject(proposalId,
                OperatorContext.fromTrustedGatewayHeader(authenticatedOperator), request.comment());
    }

    @PostMapping("/execution-jobs/{jobId}/retry")
    public Map<String, Object> retry(@PathVariable String jobId) {
        return afterSalesService.retry(jobId);
    }

    public record CreateTicketRequest(String orderId, String customerMessage) {
    }

    /** 审批请求 body：只保留审批意见 comment，绝不携带 operatorId（身份只来自可信 Header）。 */
    public record ReviewRequest(String comment) {
    }
}
