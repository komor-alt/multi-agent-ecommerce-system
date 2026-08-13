package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesAgentLoopServiceTest {

    private record CapturedEvent(String type, Map<String, Object> data) {
    }

    // 与 Spring Boot 自动配置一致：支持 java.time 序列化。
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void runCompletedEventAndRunEntityExposeNonNegativeDurationMs() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, objectMapper, 0L);

        AfterSalesRunEntity run = run();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        doAnswer(invocation -> {
            AfterSalesAgentState state = invocation.getArgument(1);
            switch ((String) invocation.getArgument(0)) {
                case AfterSalesToolExecutor.GET_ORDER_DETAIL -> state.setOrder(new AfterSalesTypes.OrderSnapshot(
                        "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                        new BigDecimal("1899000.00"), true, "customs_document_required", 10, "SF-VN-5002"));
                case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                        "SF-VN-5002", "customs_document_required", Instant.now(), 10, 0, List.of()));
                case AfterSalesToolExecutor.SEARCH_POLICY -> state.setPolicy(new AfterSalesTypes.PolicyEvidence(
                        "policy:VN_SHIPMENT_DELAY:v3#section-4.2", "VN_SHIPMENT_DELAY", "v3", "VN",
                        "SHIPMENT_DELAY", Instant.now(), 7, new BigDecimal("0.10"),
                        new BigDecimal("150000.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon"));
                case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> state.setCompensation(
                        new AfterSalesTypes.CompensationResult(true, "DELAY_COMPENSATION_COUPON",
                                new BigDecimal("150000.00"), "VND", "SHIPMENT_INACTIVE_POLICY_MATCHED"));
                case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> state.setProposalId("proposal-1");
                default -> throw new IllegalStateException("UNEXPECTED_ACTION");
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of());
        }).when(toolExecutor).execute(anyString(), any());

        service.run("run-1", "ticket-1");

        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("run_completed event missing; captured: " + appended));

        // 稳定断言：只要求非负，不依赖脆弱的严格毫秒上限。
        assertThat(runCompletedData.get("durationMs")).isInstanceOf(Number.class);
        assertThat(((Number) runCompletedData.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0);

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getDurationMs()).isNotNull();
        assertThat(savedRun.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void errorEventCarriesNonNegativeDurationMs() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        when(toolExecutor.execute(anyString(), any())).thenThrow(new IllegalStateException("BOOM"));

        service.run("run-1", "ticket-1");

        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing"));

        assertThat(errorData.get("summary")).isEqualTo("BOOM");
        assertThat(errorData.get("durationMs")).isInstanceOf(Number.class);
        assertThat(((Number) errorData.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void durationMsSurvivesJsonSerializationRoundTrip() {
        Map<String, Object> payload = Map.of(
                "summary", "done",
                "durationMs", 42L,
                "latencyMs", 7L
        );
        try {
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            assertThat(parsed.get("durationMs")).isEqualTo(42);
            assertThat(parsed.get("latencyMs")).isEqualTo(7);
        } catch (Exception error) {
            throw new AssertionError("serialization failed", error);
        }
    }

    private AfterSalesRunEntity run() {
        return AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("RUNNING")
                .maxSteps(6)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
    }

    private AfterSalesTicketEntity ticket() {
        return AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("my parcel is stuck")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .currentRunId("run-1")
                .createdAt(Instant.now())
                .build();
    }
}
