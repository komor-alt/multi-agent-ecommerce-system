package com.ecommerce.aftersales.controller;

import com.ecommerce.aftersales.service.AfterSalesRunEventService;
import com.ecommerce.aftersales.service.AfterSalesService;
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
    public Map<String, Object> analyze(@PathVariable String ticketId) {
        return afterSalesService.analyze(ticketId);
    }

    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "lastEventId", required = false) String queryLastEventId) {
        return eventService.stream(runId, lastEventId != null ? lastEventId : queryLastEventId);
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public Map<String, Object> approve(
            @PathVariable String proposalId,
            @RequestBody ReviewRequest request) {
        return afterSalesService.approve(proposalId, request.operatorId(), request.comment());
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public Map<String, Object> reject(
            @PathVariable String proposalId,
            @RequestBody ReviewRequest request) {
        return afterSalesService.reject(proposalId, request.operatorId(), request.comment());
    }

    @PostMapping("/execution-jobs/{jobId}/retry")
    public Map<String, Object> retry(@PathVariable String jobId) {
        return afterSalesService.retry(jobId);
    }

    public record CreateTicketRequest(String orderId, String customerMessage) {
    }

    public record ReviewRequest(String operatorId, String comment) {
    }
}
