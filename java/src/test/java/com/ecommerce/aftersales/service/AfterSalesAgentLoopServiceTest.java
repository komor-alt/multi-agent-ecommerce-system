package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
                intakeService(), plannerService(), routeResolver(), objectMapper, 0L);

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
                intakeService(), plannerService(), routeResolver(), objectMapper, 0L);

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
        // 补偿评估路线（含 REQUEST_REFUND）：保留既有 5 步工具流程，验证 Intake 不计入 stepCount。
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

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

        // intake_completed 事件：只含结构化字段 + 服务端决策路线 + source + latencyMs + 安全 summary。
        Map<String, Object> intakeData = appended.stream()
                .filter(event -> "intake_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("intake_completed event missing; captured: " + appended));
        assertThat(intakeData).containsEntry("issueType", "SHIPMENT_DELAY");
        assertThat(intakeData).containsEntry("source", "RULE_FALLBACK");
        assertThat(intakeData).containsEntry("fallbackReason", "RULES_MODE");
        assertThat(intakeData).containsEntry("route", "COMPENSATION_EVALUATION");
        assertThat(intakeData.get("latencyMs")).isInstanceOf(Number.class);
        @SuppressWarnings("unchecked")
        List<String> intents = (List<String>) intakeData.get("intents");
        assertThat(intents).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        // requiredEvidence 已被路线重建为服务端可信清单（补偿评估 = 全量三份）。
        @SuppressWarnings("unchecked")
        List<String> requiredEvidence = (List<String>) intakeData.get("requiredEvidence");
        assertThat(requiredEvidence).containsExactly("ORDER", "SHIPMENT", "POLICY");
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
        // 补偿评估路线（含 REQUEST_REFUND）：保留既有 ORDER+SHIPMENT+POLICY 五步流程断言。
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

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
        // 证据齐备后进入确定性决策阶段（decision_completed → 决策工具），最后 run_completed。
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

        // decision_completed 在计算补偿之后、创建方案之前（或与 calculate 之后）至少出现一次，
        // 携带路线与资格；本 stub 为 eligible=true。
        List<CapturedEvent> decisions = appended.stream()
                .filter(event -> "decision_completed".equals(event.type()))
                .toList();
        assertThat(decisions).isNotEmpty();
        assertThat(decisions.get(decisions.size() - 1).data()).containsEntry("route", "COMPENSATION_EVALUATION");

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
    void answerOnlyRouteNeedsOnlyOrderAndShipmentPrerequisites() {
        // 路线感知前置校验：ANSWER_ONLY 的合法 ORDER+SHIPMENT READY 计划不再因缺 POLICY 失败，
        // 而是直接以物流状态答复完成（见 answerOnlyRouteCompletesWithShipmentAnswer）。
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
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 仅 TRACK_SHIPMENT → ANSWER_ONLY 路线；模型建议的 requiredEvidence 是「ORDER+SHIPMENT」子集。
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "SHIPMENT"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        // 合法 ORDER+SHIPMENT READY 计划：不再 DECISION_EVIDENCE_INCOMPLETE，正常完成。
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ANSWER_DELIVERED");
        assertThat(savedRun.getStepCount()).isEqualTo(2);
    }

    @Test
    void decisionPrerequisitesAreRouteAware() {
        // 路线感知前置校验（纵深防御，正常流程 READY 已隐含证据齐备）：
        // ANSWER_ONLY 的 ORDER+SHIPMENT 齐备即合法；同一状态切到 COMPENSATION_EVALUATION
        // 路线后缺失 POLICY → DECISION_EVIDENCE_INCOMPLETE 干净失败，绝不带着缺证据进入决策工具。
        AfterSalesTicketEntity ticket = ticket();
        AfterSalesAgentState state = new AfterSalesAgentState("run-1", ticket);
        state.setRoute(DecisionRoute.ANSWER_ONLY);
        state.setOrder(new AfterSalesTypes.OrderSnapshot(
                "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                new BigDecimal("1899000.00"), true, "customs_document_required", 10, "SF-VN-5002"));
        state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                "SF-VN-5002", "customs_document_required", Instant.now(), 10, 0, List.of()));

        // ANSWER_ONLY：合法 ORDER+SHIPMENT READY 计划通过校验（不再因缺 POLICY 失败）。
        assertThatCode(() -> AfterSalesAgentLoopService.validateDecisionPrerequisites(state))
                .doesNotThrowAnyException();

        // COMPENSATION_EVALUATION：同一状态缺 POLICY → 干净失败。
        state.setRoute(DecisionRoute.COMPENSATION_EVALUATION);
        assertThatThrownBy(() -> AfterSalesAgentLoopService.validateDecisionPrerequisites(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DECISION_EVIDENCE_INCOMPLETE:POLICY");

        // POLICY 补上后通过。
        state.setPolicy(new AfterSalesTypes.PolicyEvidence(
                "policy:VN_SHIPMENT_DELAY:v3#section-4.2", "VN_SHIPMENT_DELAY", "v3", "VN",
                "SHIPMENT_DELAY", Instant.now(), 7, new BigDecimal("0.10"),
                new BigDecimal("150000.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon"));
        assertThatCode(() -> AfterSalesAgentLoopService.validateDecisionPrerequisites(state))
                .doesNotThrowAnyException();
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
                intakeService(), llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"),
                routeResolver(), objectMapper, 0L);

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
        // 默认消息无退款词 → ANSWER_ONLY 路线只要求 ORDER+SHIPMENT。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence)
                .containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        // 降级事件：ORDER 已存在后的两个后续周期各一条 planning_fallback，携带 LLM_INVALID_PLAN 与兜底证据。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(2);
        fallbacks.forEach(event -> {
            assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN");
            assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
        });
        // 首次规划周期是合法 LLM 规划：planning_completed 的 source 为 LLM，无降级事件。
        assertThat(appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> event.data().get("source"))
                .toList().get(0)).isEqualTo("LLM");
        // 路线生效：POLICY 从未被规划，工单以物流答复完成。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
    }

    @Test
    void llmPlanRequestingShipmentBeforeOrderFallsBackToOrderFirst() {
        // 场景：无 ORDER 时 LLM 规划 SHIPMENT —— 形式合法但业务前置不满足（SHIPMENT 依赖 ORDER），
        // 以 LLM_INVALID_PLAN 拒绝并规则兜底到 ORDER；ORDER 齐备后 LLM SHIPMENT 规划被接受。
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
                intakeService(), llmPlannerService("{\"nextEvidence\":\"SHIPMENT\",\"reasonCode\":\"SHIPMENT_STATUS_REQUIRED\"}"),
                routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 首次执行的是规则兜底的 get_order_detail，随后才是被接受的 LLM SHIPMENT 规划。
        InOrder inOrder = inOrder(toolExecutor);
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        // 周期 1（前置不满足）与周期 3（证据已存在）降级；周期 2 的 LLM SHIPMENT 规划被接受。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(2);
        fallbacks.forEach(event -> {
            assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN");
            assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
        });
        assertThat(appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> event.data().get("source"))
                .toList()).containsExactly("RULE_FALLBACK", "LLM", "RULE_FALLBACK");
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"))
                .getStopReason()).isEqualTo("ANSWER_DELIVERED");
    }

    @Test
    void llmPlanRequestingPolicyFallsBackUntilOrderAndShipmentArePresent() {
        // 场景 2+3：补偿评估路线下 LLM 始终规划 POLICY —— 有 ORDER 无 SHIPMENT 时兜底 SHIPMENT；
        // ORDER+SHIPMENT 齐备后 LLM→POLICY 被接受；POLICY 已存在后再兜底 READY。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                intakeService, llmPlannerService("{\"nextEvidence\":\"POLICY\",\"reasonCode\":\"POLICY_REQUIRED\"}"),
                routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 取证顺序：兜底 ORDER → 兜底 SHIPMENT → 被接受的 POLICY → 兜底 READY。
        InOrder inOrder = inOrder(toolExecutor);
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "POLICY", "READY_FOR_DECISION");
        // 周期 1（缺 ORDER）、2（缺 SHIPMENT）、4（POLICY 已存在）降级；周期 3 的 LLM POLICY 被接受。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(3);
        fallbacks.forEach(event -> assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN"));
        assertThat(appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> event.data().get("source"))
                .toList()).containsExactly("RULE_FALLBACK", "RULE_FALLBACK", "LLM", "RULE_FALLBACK");
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(5);
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
                intakeService(), plannerService, routeResolver(), objectMapper, 0L);

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
    void untrustedModelEvidenceSuggestionIsReplacedByTrustedRouteEvidence() {
        // 模型/规则的 requiredEvidence 只是不可信建议：含未知证据（INVOICE）或试图抬高
        // （补 POLICY）都不能影响取证 —— 路线解析后全部替换为服务端清单（ORDER+SHIPMENT）。
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
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 建议含未知证据与 POLICY（试图抬高）：TRACK_SHIPMENT 单意图 → ANSWER_ONLY 路线。
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "INVOICE", "POLICY"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        // planning_started 携带的 requiredEvidence 是路线重建后的可信清单，而不是模型建议。
        List<Map<String, Object>> planningStarted = appended.stream()
                .filter(event -> "planning_started".equals(event.type()))
                .map(CapturedEvent::data)
                .toList();
        assertThat(planningStarted).isNotEmpty();
        planningStarted.forEach(data -> {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) data.get("requiredEvidence");
            assertThat(required).containsExactly("ORDER", "SHIPMENT");
        });
        // 未知证据 INVOICE 与 POLICY 从未进入取证序列；工单正常以 ANSWER_DELIVERED 完成。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ANSWER_DELIVERED");
    }

    @Test
    void llmPlannerNeverSeesUntrustedModelEvidenceSuggestion() {
        // LLM 模式回归：即使模型建议含未知证据，循环也在规划之前用路线清单替换，
        // LLM 规划器只拿到可信 ORDER+SHIPMENT，工单正常完成（PLANNER_INVALID_REQUIRED_EVIDENCE 不再可达）。
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
                intakeService, llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"),
                routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "INVOICE"), "RULE_FALLBACK", "RULES_MODE", 0L));

        service.run("run-1", "ticket-1");

        // 工单完成（LLM 规划 ORDER → 规则兜底 SHIPMENT → READY），没有 invalidInput 失败。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ANSWER_DELIVERED");
    }

    @Test
    void maxStepsBoundsBothEvidenceAndDecisionTools() {
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
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

        // 补偿评估路线（REQUEST_REFUND）；maxSteps=4：三份取证 + calculate 恰好占满预算，
        // create_action_proposal 绝不允许超出预算执行。
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run(4)));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());

        service.run("run-1", "ticket-1");

        // 三份取证 + calculate 都在预算内执行；create_action_proposal 超出预算被拦截。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
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
        assertThat(savedRun.getStepCount()).isEqualTo(4);
    }

    @Test
    void answerOnlyRouteCompletesWithShipmentAnswerWithoutForbiddenTools() {
        // 仅查询物流（无退款词）→ ANSWER_ONLY：Planner 精确序列 ORDER → SHIPMENT → READY；
        // 禁止工具（POLICY / CALCULATE_COMPENSATION / CREATE_ACTION_PROPOSAL）绝不调用；
        // decision_completed + run COMPLETED / ticket RESOLVED / ANSWER_DELIVERED / stepCount 2 /
        // requiresApproval false / answerType SHIPMENT_STATUS + 结构化物流答复与证据 ID。
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
                intakeService(), plannerService(), routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // Planner 精确序列：ORDER → SHIPMENT → READY_FOR_DECISION（无 POLICY）。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());

        // decision_completed：route / answerType / 结构化答复 / 证据 ID。
        Map<String, Object> decisionData = appended.stream()
                .filter(event -> "decision_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("decision_completed event missing; captured: " + appended));
        assertThat(decisionData).containsEntry("route", "ANSWER_ONLY");
        assertThat(decisionData).containsEntry("requiresApproval", false);
        assertThat(decisionData).containsEntry("answerType", "SHIPMENT_STATUS");
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) decisionData.get("answer");
        assertThat(answer).containsEntry("inactiveDays", 10);

        // 终态：run COMPLETED / RESOLVED / ANSWER_DELIVERED / stepCount 2。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ANSWER_DELIVERED");
        assertThat(savedRun.getStepCount()).isEqualTo(2);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.RESOLVED);

        // finalAnswer：route / requiresApproval false / answerType SHIPMENT_STATUS / 证据 ID。
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsEntry("decisionRoute", "ANSWER_ONLY");
        assertThat(finalAnswer).containsEntry("requiresApproval", false);
        assertThat(finalAnswer).containsEntry("answerType", "SHIPMENT_STATUS");
        assertThat(finalAnswer.get("answer")).isNotNull();
        assertThat(finalAnswer.get("evidenceIds")).isNotNull();
        assertThat(finalAnswer).doesNotContainKey("proposalId");
    }

    @Test
    void compensationRouteNotEligibleCompletesWithoutProposal() {
        // 补偿评估但未达政策阈值（eligible=false）：decision_completed 后绝不调用 create_action_proposal；
        // run COMPLETED / RESOLVED / NO_ACTION_REQUIRED / stepCount 4 / requiresApproval false /
        // action NO_ACTION / reason POLICY_THRESHOLD_NOT_REACHED。
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
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());
        // 补偿结果不可补偿：政策阈值未达到（如 O-VN-5003 仅 2 天未更新）。
        doAnswer(invocation -> {
            AfterSalesAgentState state = invocation.getArgument(1);
            if (AfterSalesToolExecutor.CALCULATE_COMPENSATION.equals(invocation.getArgument(0))) {
                state.setCompensation(new AfterSalesTypes.CompensationResult(
                        false, "NO_ACTION", BigDecimal.ZERO, "VND", "POLICY_THRESHOLD_NOT_REACHED"));
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of());
        }).when(toolExecutor).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());

        service.run("run-1", "ticket-1");

        // decision_completed 携带 eligible=false / NO_ACTION / 阈值原因；绝不调用 create_action_proposal。
        Map<String, Object> decisionData = appended.stream()
                .filter(event -> "decision_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("decision_completed event missing; captured: " + appended));
        assertThat(decisionData).containsEntry("route", "COMPENSATION_EVALUATION");
        assertThat(decisionData).containsEntry("eligible", false);
        assertThat(decisionData).containsEntry("requiresApproval", false);
        assertThat(decisionData).containsEntry("action", "NO_ACTION");
        assertThat(decisionData).containsEntry("reason", "POLICY_THRESHOLD_NOT_REACHED");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        // 终态：run COMPLETED / RESOLVED / NO_ACTION_REQUIRED / stepCount 4。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("NO_ACTION_REQUIRED");
        assertThat(savedRun.getStepCount()).isEqualTo(4);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.RESOLVED);

        // finalAnswer：route / eligible false / requiresApproval false / NO_ACTION / 阈值原因。
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsEntry("decisionRoute", "COMPENSATION_EVALUATION");
        assertThat(finalAnswer).containsEntry("eligible", false);
        assertThat(finalAnswer).containsEntry("requiresApproval", false);
        assertThat(finalAnswer).containsEntry("action", "NO_ACTION");
        assertThat(finalAnswer).containsEntry("reason", "POLICY_THRESHOLD_NOT_REACHED");
        assertThat(finalAnswer).doesNotContainKey("proposalId");
    }

    @Test
    void compensationRouteEligibleCreatesProposalAndWaitsForApproval() {
        // 补偿评估且可达政策阈值（eligible=true）：decision_completed 后创建方案；
        // run COMPLETED / PENDING_APPROVAL / ACTION_PROPOSAL_CREATED / requiresApproval true / stepCount 5。
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
                intakeService, plannerService(), routeResolver(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        when(intakeService.classify(anyString())).thenReturn(refundIntake());

        service.run("run-1", "ticket-1");

        // decision_completed 在 create_action_proposal 之前；eligible=true。
        List<CapturedEvent> decisions = appended.stream()
                .filter(event -> "decision_completed".equals(event.type()))
                .toList();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).data()).containsEntry("route", "COMPENSATION_EVALUATION");
        assertThat(decisions.get(0).data()).containsEntry("eligible", true);
        assertThat(decisions.get(0).data()).containsEntry("requiresApproval", true);
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        // 终态：run COMPLETED / PENDING_APPROVAL / ACTION_PROPOSAL_CREATED / stepCount 5。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(5);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);

        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsEntry("decisionRoute", "COMPENSATION_EVALUATION");
        assertThat(finalAnswer).containsEntry("requiresApproval", true);
        assertThat(finalAnswer).containsEntry("eligible", true);
        assertThat(finalAnswer).containsEntry("proposalId", "proposal-1");
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

    /** 确定性路线解析器：无网络、无状态，与生产实现一致。 */
    private DecisionRouteResolver routeResolver() {
        return new DecisionRouteResolver();
    }

    /** 补偿评估路线的 Intake 分类（含 REQUEST_REFUND）：复用 5 步补偿流程的测试替身。 */
    private AfterSalesTypes.IntakeResult refundIntake() {
        return new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "SHIPMENT", "POLICY"), "RULE_FALLBACK", "RULES_MODE", 0L);
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
