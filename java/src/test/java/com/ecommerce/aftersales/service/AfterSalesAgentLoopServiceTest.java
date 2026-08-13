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
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService(), objectMapper, 0L);

        AfterSalesRunEntity run = run();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
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
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
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
    void intakeClassifiedAndExposedWithoutCountingToolSteps() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
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

        // intake_completed 事件：只含结构化字段 + source + latencyMs + 安全 summary。
        Map<String, Object> intakeData = appended.stream()
                .filter(event -> "intake_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("intake_completed event missing; captured: " + appended));
        assertThat(intakeData).containsEntry("issueType", "SHIPMENT_DELAY");
        assertThat(intakeData).containsEntry("source", "RULE_FALLBACK");
        assertThat(intakeData).containsEntry("fallbackReason", "RULES_MODE");
        assertThat(intakeData.get("latencyMs")).isInstanceOf(Number.class);
        @SuppressWarnings("unchecked")
        List<String> intents = (List<String>) intakeData.get("intents");
        assertThat(intents).containsExactly("TRACK_SHIPMENT");
        // 工具事件仍只有既定五个（get_order_detail / get_shipment_trace / search_policy /
        // calculate_compensation / create_action_proposal），Intake 不计入 stepCount。
        long toolEvents = appended.stream()
                .filter(event -> "tool_completed".equals(event.type()) || "retrieval_completed".equals(event.type()))
                .count();
        assertThat(toolEvents).isEqualTo(5);

        // run_completed.finalAnswer 含 intake。
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsKey("intake");

        // 持久化 run.finalAnswerJson 含 intake，stepCount 不变。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStepCount()).isEqualTo(5);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> persistedAnswer = objectMapper.readValue(savedRun.getFinalAnswerJson(), Map.class);
            assertThat(persistedAnswer).containsKey("intake");
        } catch (Exception error) {
            throw new AssertionError("finalAnswerJson unreadable", error);
        }
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

    @Test
    void plannerDrivenRunEmitsPlannerEventsInOrderWithStepCountFive() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 事件顺序：每次取证规划周期 = planning_started → planning_completed（RULES 模式无降级事件），
        // 证据齐备后进入确定性决策阶段，最后 run_completed。
        List<String> observed = appended.stream()
                .map(event -> switch (event.type()) {
                    case "planning_completed" -> "planning:" + event.data().get("nextEvidence");
                    case "tool_completed", "retrieval_completed" -> "tool:" + event.data().get("action");
                    default -> event.type();
                })
                .filter(item -> item.startsWith("planning:") || item.startsWith("tool:"))
                .toList();
        assertThat(observed).containsExactly(
                "planning:ORDER", "tool:get_order_detail",
                "planning:SHIPMENT", "tool:get_shipment_trace",
                "planning:POLICY", "tool:search_after_sales_policy",
                "planning:READY_FOR_DECISION",
                "tool:calculate_compensation", "tool:create_action_proposal");

        // Planner 事件：4 次规划周期，每次 planning_started + planning_completed，携带证据与来源。
        assertThat(appended.stream().filter(event -> "planning_started".equals(event.type())).count()).isEqualTo(4);
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence)
                .containsExactly("ORDER", "SHIPMENT", "POLICY", "READY_FOR_DECISION");
        appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .forEach(event -> {
                    assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
                    assertThat(event.data().get("reasonCode")).isInstanceOf(String.class);
                    assertThat(event.data().get("latencyMs")).isInstanceOf(Number.class);
                });

        // 默认 ORDER+SHIPMENT+POLICY 场景：工具 stepCount 总数保持 5。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStepCount()).isEqualTo(5);
    }

    @Test
    void requiredEvidenceSubsetSkipsPolicyButDecisionGateFailsCleanly() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, plannerService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 服务端重建的必需证据子集：ORDER+SHIPMENT，不包含 POLICY。
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "SHIPMENT"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        // 规划语义保留：子集场景 POLICY 从未被请求，也没有 search_after_sales_policy 工具调用。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());

        // 生产 AfterSalesToolExecutor 要求 policy 才能计算金额/上限/资格：决策前置校验必须拦截。
        // 子集工单绝不以「4 步完成」收尾 —— 缺少政策证据时不得调用任何决策工具，干净失败。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());

        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing; captured: " + appended));
        assertThat(errorData.get("summary")).isEqualTo("DECISION_EVIDENCE_INCOMPLETE:POLICY");

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("DECISION_EVIDENCE_INCOMPLETE:POLICY");
    }

    @Test
    void llmPlanRequestingAlreadyPresentEvidenceFallsBackWithoutDuplicatingGetOrderDetail() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 模型每次都请求 ORDER（合法严格输出，含配对理由码）：第一次合法执行；之后请求已存在证据
        // 被拒绝（LLM_INVALID_PLAN），直接规则兜底到下一份缺失证据，get_order_detail 绝不重复执行。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence)
                .containsExactly("ORDER", "SHIPMENT", "POLICY", "READY_FOR_DECISION");
        // 降级事件：三个后续周期各一条 planning_fallback，携带 LLM_INVALID_PLAN 与兜底证据。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(3);
        fallbacks.forEach(event -> {
            assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN");
            assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
        });
        // 首次规划周期是合法 LLM 规划：planning_completed 的 source 为 LLM，无降级事件。
        assertThat(appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> event.data().get("source"))
                .toList().get(0)).isEqualTo("LLM");
    }

    @Test
    void plannerWithoutProgressFailsRunCleanly() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // 故障 Planner：模型声称证据就绪（但状态里没有任何证据），规则兜底也不修复 —— 无法推进。
        AfterSalesEvidencePlannerService plannerService = mock(AfterSalesEvidencePlannerService.class);
        when(plannerService.plan(any())).thenReturn(new AfterSalesTypes.PlanningResult(
                AfterSalesTypes.EvidenceType.READY_FOR_DECISION, "EVIDENCE_COMPLETE", "LLM", null, 0));
        when(plannerService.deterministicPlan(any(), anyString())).thenReturn(new AfterSalesTypes.PlanningResult(
                AfterSalesTypes.EvidenceType.READY_FOR_DECISION, "EVIDENCE_COMPLETE", "RULE_FALLBACK",
                "LLM_INVALID_PLAN", 0));
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService, objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.run("run-1", "ticket-1");

        // 工单以 PLANNER_NO_PROGRESS 失败，未执行任何工具。
        verify(toolExecutor, never()).execute(anyString(), any());
        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing"));
        assertThat(errorData.get("summary")).isEqualTo("PLANNER_NO_PROGRESS");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("PLANNER_NO_PROGRESS");
    }

    @Test
    void plannerInvalidRequiredEvidenceFailsRunCleanly() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, plannerService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // requiredEvidence 含未知证据：真实 RULES Planner 返回 invalidInput 结果，循环必须拒绝，
        // 而不是把未知证据静默跳过并声称 READY。
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "INVOICE"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        verify(toolExecutor, never()).execute(anyString(), any());
        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing; captured: " + appended));
        assertThat(errorData.get("summary")).isEqualTo("PLANNER_INVALID_REQUIRED_EVIDENCE");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("PLANNER_INVALID_REQUIRED_EVIDENCE");
    }

    @Test
    void llmPlannerInvalidRequiredEvidenceFailsRunBeforeAnyToolExecutes() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // LLM 模式回归：requiredEvidence 含未知证据时，规划器必须在调用模型之前返回 invalidInput。
        // 即使模型本可返回合法输出（这里注入合法 ORDER 输出），非法输入也绝不允许先走模型、
        // 执行取证工具、最后才以其它错误码失败。
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"),
                objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "INVOICE"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        // 任何证据工具都不得执行；失败发生在第一个规划周期，早于任何 planning_completed。
        verify(toolExecutor, never()).execute(anyString(), any());
        assertThat(appended.stream().filter(event -> "planning_started".equals(event.type())).count()).isEqualTo(1);
        assertThat(appended.stream().filter(event -> "planning_completed".equals(event.type())).count()).isZero();
        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing; captured: " + appended));
        assertThat(errorData.get("summary")).isEqualTo("PLANNER_INVALID_REQUIRED_EVIDENCE");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("PLANNER_INVALID_REQUIRED_EVIDENCE");
    }

    @Test
    void maxStepsBoundsBothEvidenceAndDecisionTools() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService(), plannerService(), objectMapper, 0L);

        // maxSteps=3：三份取证恰好占满预算；决策工具绝不允许超出预算执行。
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run(3)));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 三份取证都在预算内执行；任何决策工具都不执行。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());

        Map<String, Object> errorData = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing; captured: " + appended));
        assertThat(errorData.get("summary")).isEqualTo("MAX_STEPS_EXCEEDED");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("MAX_STEPS_EXCEEDED");
        assertThat(savedRun.getStepCount()).isEqualTo(3);
    }

    /** 与既有测试相同的工具替身：按动作写入状态，返回 stub 结果。 */
    private void stubToolExecutor(AfterSalesToolExecutor toolExecutor) {
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
    }

    /** LLM 模式 Planner 测试替身：注入模型输出，测试中不发真实网络请求。 */
    private AfterSalesEvidencePlannerService llmPlannerService(String response) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "LLM", 1000, "test-key-123") {
            @Override
            protected String callModel(String prompt) {
                return response;
            }
        };
    }

    /** RULES 模式 Intake：不发网络请求，分类结果确定，用于 Agent Loop 集成测试。 */
    private AfterSalesIntakeService intakeService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesIntakeService(builder, objectMapper, "RULES", 1000, "your_api_key_here");
    }

    /** RULES 模式 Planner：不发网络请求，确定性规划，用于 Agent Loop 集成测试。 */
    private AfterSalesEvidencePlannerService plannerService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "RULES", 1000, "your_api_key_here");
    }

    private AfterSalesRunEntity run() {
        return run(6);
    }

    private AfterSalesRunEntity run(int maxSteps) {
        return AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("RUNNING")
                .maxSteps(maxSteps)
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
