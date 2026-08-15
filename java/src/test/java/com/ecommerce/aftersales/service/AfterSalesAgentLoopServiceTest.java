package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"),
                routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L, 9);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), llmPlannerService("{\"nextEvidence\":\"SHIPMENT\",\"reasonCode\":\"SHIPMENT_STATUS_REQUIRED\"}"),
                routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L, 9);

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
    void consecutiveInvalidPolicyPlansEscalateBeforeSecondTool() {
        // 连续降级升级：LLM 始终规划 POLICY（业务前置不满足）→ 第一次兜底 ORDER 并执行
        // GET_ORDER_DETAIL；第二次连续 LLM_INVALID_PLAN 兜底 SHIPMENT，但升级先于执行 ——
        // 绝不执行物流/政策/补偿/方案工具。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, llmPlannerService("{\"nextEvidence\":\"POLICY\",\"reasonCode\":\"POLICY_REQUIRED\"}"),
                routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L, 9);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 规划序列：兜底 ORDER → 兜底 SHIPMENT；第二次降级后升级先于执行，只有 ORDER 落库。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT");
        // 周期 1 与周期 2 都是 LLM_INVALID_PLAN 连续降级；没有接受过任何 LLM 规划。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(2);
        fallbacks.forEach(event -> {
            assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN");
            assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
        });
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ESCALATED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("PLANNER_CONSECUTIVE_FALLBACK");
        assertThat(savedRun.getStepCount()).isEqualTo(1);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues()).anyMatch(
                item -> AfterSalesTypes.TicketStatus.ESCALATED.equals(item.getStatus()));
        verify(eventService).complete("run-1");
    }

    @Test
    void plannerWithoutProgressFailsRunCleanly() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), plannerService, routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
    void untrustedEvidenceIsSanitizedBeforeConsecutiveFallbackEscalation() {
        // LLM 模式回归 + 连续降级升级：模型建议含未知证据（INVOICE），循环在规划前用路线
        // 清单替换（LLM 规划器只拿到可信 ORDER+SHIPMENT）；模型反复规划已获证据 ORDER，
        // 第 2、3 次规划周期连续降级（LLM_INVALID_PLAN）→ 第 3 次规划 READY_FOR_DECISION
        // 正是第二次连续降级，决策前立即转人工升级（PLANNER_CONSECUTIVE_FALLBACK）。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, llmPlannerService("{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}"),
                routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L, 9);

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

        // intake_completed：路线重建后的可信 requiredEvidence 恰为 ORDER+SHIPMENT，INVOICE 从未出现。
        Map<String, Object> intakeData = appended.stream()
                .filter(event -> "intake_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("intake_completed event missing; captured: " + appended));
        @SuppressWarnings("unchecked")
        List<String> trustedRequired = (List<String>) intakeData.get("requiredEvidence");
        assertThat(trustedRequired).containsExactly("ORDER", "SHIPMENT");
        assertThat(trustedRequired).doesNotContain("INVOICE");

        // 取证序列严格 ORDER → SHIPMENT；第 3 次规划 READY_FOR_DECISION 是第二次连续降级。
        InOrder inOrder = inOrder(toolExecutor);
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "SHIPMENT", "READY_FOR_DECISION");
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(2);
        fallbacks.forEach(event -> {
            assertThat(event.data()).containsEntry("fallbackReason", "LLM_INVALID_PLAN");
            assertThat(event.data()).containsEntry("source", "RULE_FALLBACK");
        });
        assertThat(fallbacks.get(1).data()).containsEntry("nextEvidence", "READY_FOR_DECISION");

        // 决策前升级：没有 policy / compensation / proposal / error，也没有进入决策阶段。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).noneMatch(event -> "decision_completed".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("run_completed event missing; captured: " + appended));
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).doesNotContainKeys("policy", "compensation", "proposalId");

        // 终态：run ESCALATED / PLANNER_CONSECUTIVE_FALLBACK / stepCount 2，工单 ESCALATED。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ESCALATED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("PLANNER_CONSECUTIVE_FALLBACK");
        assertThat(savedRun.getStepCount()).isEqualTo(2);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues()).anyMatch(
                item -> AfterSalesTypes.TicketStatus.ESCALATED.equals(item.getStatus()));
        verify(eventService).complete("run-1");
    }

    @Test
    void maxStepsBoundsBothEvidenceAndDecisionTools() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService(), plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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

    @Test
    void lostInTransitRunCollectsCarrierCaseGraphAndCreatesRefundProposal() {
        // LOST_IN_TRANSIT 补偿评估：Planner 精确序列 ORDER → SHIPMENT → CARRIER_CASE → POLICY →
        // READY；决策阶段 calculate + create_proposal；绝不调用交付图工具（无跨图取证）。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(lostIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 精确证据图序列（与 LOST_IN_TRANSIT 图一致，含 CARRIER_CASE 节点）。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence)
                .containsExactly("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY", "READY_FOR_DECISION");
        // 工具调用顺序与图一致，且绝不调用交付图工具（无跨图取证）。
        List<String> toolActions = appended.stream()
                .filter(event -> "tool_completed".equals(event.type()) || "retrieval_completed".equals(event.type()))
                .map(event -> (String) event.data().get("action"))
                .toList();
        assertThat(toolActions).containsExactly(
                "get_order_detail", "get_shipment_trace", "get_carrier_case",
                "search_after_sales_policy", "calculate_compensation", "create_action_proposal");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_DELIVERY_PROOF), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_DAMAGE_PHOTO), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_PRODUCT), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(6);
        // 可信分类结果已同步工单问题类型（种子 SHIPMENT_DELAY → LOST_IN_TRANSIT）。
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getIssueType)
                .toList()).contains("LOST_IN_TRANSIT");
    }

    @Test
    void damagedItemRunCollectsDeliveryGraphWithoutShipmentAndCreatesProposal() {
        // DAMAGED_ITEM 补偿评估：Planner 精确序列 ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT →
        // POLICY → READY（7 步工具 = 5 取证 + 2 决策，maxSteps=8 预算内）；
        // 绝不调用 get_shipment_trace / get_carrier_case（图没有这些节点）。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(damagedIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run(8)));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 工单已有持久化图片附件（服务端核验结论 VERIFIED）：DAMAGE_PHOTO 前置校验通过，继续取证。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of(
                TicketAttachmentEntity.builder()
                        .id("att-photo-1")
                        .ticketId("ticket-1")
                        .messageId("message-1")
                        .fileName("damage.jpg")
                        .contentType("image/jpeg")
                        .storageKey("objects/att-photo-1")
                        .metadataJson("{\"reviewStatus\":\"VERIFIED\"}")
                        .createdAt(Instant.now())
                        .build()));

        service.run("run-1", "ticket-1");

        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly(
                "ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY", "READY_FOR_DECISION");
        List<String> toolActions = appended.stream()
                .filter(event -> "tool_completed".equals(event.type()) || "retrieval_completed".equals(event.type()))
                .map(event -> (String) event.data().get("action"))
                .toList();
        assertThat(toolActions).containsExactly(
                "get_order_detail", "get_delivery_proof", "get_damage_photo", "get_product",
                "search_after_sales_policy", "calculate_compensation", "create_action_proposal");
        // 无跨图取证：SHIPMENT / CARRIER_CASE 绝不调用。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_CARRIER_CASE), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(7);
    }

    @Test
    void damagedItemAnswerOnlyAnswersWithDeliveryStatus() {
        // DAMAGED_ITEM 纯查询（无退款词）→ ANSWER_ONLY：Planner 精确序列 ORDER → DELIVERY →
        // READY；decision_completed 以 DELIVERY_STATUS 答复交付证明；禁止工具绝不调用。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "DAMAGED_ITEM", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "DELIVERY"), "RULE_FALLBACK", "RULES_MODE", 0L));
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService, attachmentRepository,
                intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "DELIVERY", "READY_FOR_DECISION");
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());

        Map<String, Object> decisionData = appended.stream()
                .filter(event -> "decision_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("decision_completed event missing"));
        assertThat(decisionData).containsEntry("route", "ANSWER_ONLY");
        assertThat(decisionData).containsEntry("requiresApproval", false);
        assertThat(decisionData).containsEntry("answerType", "DELIVERY_STATUS");
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) decisionData.get("answer");
        assertThat(answer).containsEntry("status", "DELIVERED");
        assertThat(answer).containsKey("deliveredAt");

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
    void damagedTicketWithoutPhotoFinishesWaitingCustomerWithResumableSnapshot() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(damagedIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run(8)));
        AfterSalesTicketEntity ticket = ticket();
        ticket.setIssueType("DAMAGED_ITEM");
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 工单没有持久化图片附件：规划到 DAMAGE_PHOTO 时转为 REQUEST_MORE_INFO，不抛通用失败。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of());

        service.run("run-1", "ticket-1");

        // 只收集了 ORDER + DELIVERY（2 步），没有 error、没有方案。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence).containsExactly("ORDER", "DELIVERY", "DAMAGE_PHOTO");

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "WAITING_CUSTOMER".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WAITING_CUSTOMER run not saved; captured: "
                        + runCaptor.getAllValues().stream().map(AfterSalesRunEntity::getStatus).toList()));
        assertThat(savedRun.getStopReason()).isEqualTo("CUSTOMER_INFO_REQUIRED");
        assertThat(savedRun.getStepCount()).isEqualTo(2);
        assertThat(savedRun.getResumeStateJson()).isNotBlank();

        // request_more_info 事件先于 run_completed 发出：missingInfo + 安全客户请求文案。
        CapturedEvent requestMoreInfo = appended.stream()
                .filter(event -> "request_more_info".equals(event.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("request_more_info event missing; captured: " + appended));
        assertThat(requestMoreInfo.data()).containsEntry("missingInfo", List.of("DAMAGE_PHOTO"));
        assertThat(String.valueOf(requestMoreInfo.data().get("customerRequest"))).isNotBlank();

        // finalAnswer：missingInfo [DAMAGE_PHOTO] + 安全客户请求，route = REQUEST_MORE_INFO。
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsEntry("decisionRoute", "REQUEST_MORE_INFO");
        assertThat(finalAnswer).containsEntry("requiresApproval", false);
        assertThat(finalAnswer.get("missingInfo")).isEqualTo(List.of("DAMAGE_PHOTO"));
        assertThat(String.valueOf(finalAnswer.get("customerRequest"))).isNotBlank();

        // 可恢复快照：ORDER/DELIVERY/intake/路线/证据 ID 全部持久化，供补充照片后的新 run 还原。
        try {
            AfterSalesTypes.ResumeState resume = objectMapper.readValue(
                    savedRun.getResumeStateJson(), AfterSalesTypes.ResumeState.class);
            assertThat(resume.order()).isNotNull();
            assertThat(resume.delivery()).isNotNull();
            assertThat(resume.intake().issueType()).isEqualTo("DAMAGED_ITEM");
            assertThat(resume.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
            assertThat(resume.requiredEvidence()).containsExactly(
                    "ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
            assertThat(resume.evidenceIds()).hasSize(2);
        } catch (Exception error) {
            throw new AssertionError("resumeStateJson unreadable", error);
        }

        // 工单进入 WAITING_CUSTOMER；SSE 正常收尾。
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        verify(eventService).complete("run-1");
    }

    @Test
    void resumedRunRestoresParentStateAndNeverRepeatsCollectedTools() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        // 父 run（WAITING_CUSTOMER）携带可恢复快照：ORDER/DELIVERY/intake/路线/证据 ID。
        AfterSalesRunEntity parentRun = AfterSalesRunEntity.builder()
                .id("run-parent")
                .ticketId("ticket-1")
                .status("WAITING_CUSTOMER")
                .maxSteps(8)
                .stepCount(2)
                .stopReason("CUSTOMER_INFO_REQUIRED")
                .completedAt(Instant.now())
                .finalAnswerJson("{}")
                .resumeStateJson(writeResumeStateJson("run-parent"))
                .build();
        AfterSalesRunEntity resumedRun = AfterSalesRunEntity.builder()
                .id("run-2")
                .ticketId("ticket-1")
                .parentRunId("run-parent")
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.findById("run-2")).thenReturn(Optional.of(resumedRun));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(parentRun));
        AfterSalesTicketEntity ticket = ticket();
        ticket.setIssueType("DAMAGED_ITEM");
        ticket.setStatus(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-2");
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 客户已补充图片附件（服务端核验结论 VERIFIED）：恢复后继续取证。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of(
                TicketAttachmentEntity.builder()
                        .id("att-photo-1")
                        .ticketId("ticket-1")
                        .messageId("message-2")
                        .fileName("damage.jpg")
                        .contentType("image/jpeg")
                        .storageKey("objects/att-photo-1")
                        .metadataJson("{\"reviewStatus\":\"VERIFIED\"}")
                        .createdAt(Instant.now())
                        .build()));

        service.run("run-2", "ticket-1");

        // run_resumed 事件：结构化还原信息（父 run + 已还原证据 ID），且跳过 Intake 分类。
        CapturedEvent resumed = appended.stream()
                .filter(event -> "run_resumed".equals(event.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run_resumed event missing; captured: " + appended));
        assertThat(resumed.data()).containsEntry("parentRunId", "run-parent");
        assertThat(resumed.data().get("restoredEvidenceIds")).isEqualTo(List.of(
                "order:O-SG-1001:v1", "delivery:O-SG-1001:2026-08-10"));
        assertThat(appended).noneMatch(event -> "intake_started".equals(event.type()));

        // 绝不重复父 run 已收集的 ORDER / DELIVERY 工具。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_DELIVERY_PROOF), any());
        // 从 DAMAGE_PHOTO 继续：照片 → 商品 → 政策 → 计算 → 方案。
        List<String> plannedEvidence = appended.stream()
                .filter(event -> "planning_completed".equals(event.type()))
                .map(event -> (String) event.data().get("nextEvidence"))
                .toList();
        assertThat(plannedEvidence)
                .containsExactly("DAMAGE_PHOTO", "PRODUCT", "POLICY", "READY_FOR_DECISION");
        InOrder inOrder = inOrder(toolExecutor);
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_DAMAGE_PHOTO), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.GET_PRODUCT), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        inOrder.verify(toolExecutor).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());

        // 恢复后的 run 正常完成：方案待审批；finalAnswer 还原了父 run 的 ORDER/DELIVERY 快照
        // 与其证据 ID（证据链延续，不重复取证）。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved"));
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(5);
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        // 证据链延续：父 run 的 ORDER/DELIVERY 证据 ID 保留在链首，随后追加本次新取证 ID。
        @SuppressWarnings("unchecked")
        List<String> evidenceIds = (List<String>) finalAnswer.get("evidenceIds");
        assertThat(evidenceIds).containsExactly(
                "order:O-SG-1001:v1", "delivery:O-SG-1001:2026-08-10",
                "damage-photo:DP-SG-1001-01:v1", "product:P001:v1",
                "policy:VN_SHIPMENT_DELAY:v3#section-4.2");
        AfterSalesTypes.OrderSnapshot restoredOrder = (AfterSalesTypes.OrderSnapshot) finalAnswer.get("order");
        assertThat(restoredOrder.orderId()).isEqualTo("O-SG-1001");
    }

    @Test
    void resumedRunWithoutPhotoAsksForPhotoAgainWithoutRepeatingTools() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        AfterSalesRunEntity parentRun = AfterSalesRunEntity.builder()
                .id("run-parent")
                .ticketId("ticket-1")
                .status("WAITING_CUSTOMER")
                .maxSteps(8)
                .stepCount(2)
                .stopReason("CUSTOMER_INFO_REQUIRED")
                .completedAt(Instant.now())
                .resumeStateJson(writeResumeStateJson("run-parent"))
                .build();
        AfterSalesRunEntity resumedRun = AfterSalesRunEntity.builder()
                .id("run-2")
                .ticketId("ticket-1")
                .parentRunId("run-parent")
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.findById("run-2")).thenReturn(Optional.of(resumedRun));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(parentRun));
        AfterSalesTicketEntity ticket = ticket();
        ticket.setIssueType("DAMAGED_ITEM");
        ticket.setStatus(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-2");
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        // 客户补充了消息但没有任何图片附件：仍然等待照片，且不重复已收集工具。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of());

        service.run("run-2", "ticket-1");

        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_DELIVERY_PROOF), any());
        verify(toolExecutor, never()).execute(anyString(), any());
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "WAITING_CUSTOMER".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason()).isEqualTo("CUSTOMER_INFO_REQUIRED");
        assertThat(savedRun.getStepCount()).isZero();
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
    }

    @Test
    void resumedRunFailsClosedWhenParentSnapshotIsMissing() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        // 父 run 缺失可恢复快照：不安全恢复，整条 run 干净失败，绝不无快照裸跑。
        AfterSalesRunEntity parentRun = AfterSalesRunEntity.builder()
                .id("run-parent")
                .ticketId("ticket-1")
                .status("COMPLETED")
                .maxSteps(8)
                .stepCount(2)
                .stopReason("ANSWER_DELIVERED")
                .completedAt(Instant.now())
                .build();
        AfterSalesRunEntity resumedRun = AfterSalesRunEntity.builder()
                .id("run-2")
                .ticketId("ticket-1")
                .parentRunId("run-parent")
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.findById("run-2")).thenReturn(Optional.of(resumedRun));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(parentRun));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());

        service.run("run-2", "ticket-1");

        CapturedEvent error = appended.stream()
                .filter(event -> "error".equals(event.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("error event missing"));
        assertThat(error.data().get("summary")).isEqualTo("PARENT_RUN_NOT_RESUMABLE");
        verify(toolExecutor, never()).execute(anyString(), any());
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason()).isEqualTo("PARENT_RUN_NOT_RESUMABLE");
    }

    // ==================================================================
    // Phase 3：HUMAN_ESCALATION / WAITING_EXTERNAL
    // ==================================================================

    @Test
    void unsupportedIssueCompletesEscalatedImmediatelyWithoutAnyTool() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(new AfterSalesTypes.IntakeResult(
                "UNSUPPORTED", List.of("CANCEL_ORDER"), "LOW", Map.of(), List.of(),
                List.of(), "RULE_FALLBACK", "RULES_MODE", 0L));
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.run("run-1", "ticket-1");

        // 零工具、零方案：UNSUPPORTED 不进入取证循环。
        verify(toolExecutor, never()).execute(anyString(), any());
        verify(toolExecutor, never()).trustedArguments(anyString(), any());
        // Intake 路线重建为 HUMAN_ESCALATION，空必需证据。
        Map<String, Object> intakeData = appended.stream()
                .filter(event -> "intake_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        assertThat(intakeData).containsEntry("route", "HUMAN_ESCALATION");
        assertThat(intakeData.get("requiredEvidence")).isEqualTo(List.of());

        // 终态：run ESCALATED / stopReason UNSUPPORTED_ISSUE_TYPE / stepCount 0 / ticket ESCALATED。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ESCALATED run not saved; captured: "
                        + runCaptor.getAllValues().stream().map(AfterSalesRunEntity::getStatus).toList()));
        assertThat(savedRun.getStopReason())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_UNSUPPORTED_ISSUE_TYPE);
        assertThat(savedRun.getStepCount()).isZero();
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        AfterSalesTicketEntity savedTicket = ticketCaptor.getAllValues().stream()
                .filter(item -> item.getStatus() == AfterSalesTypes.TicketStatus.ESCALATED)
                .findFirst()
                .orElseThrow();
        assertThat(savedTicket.getEscalationReasonJson()).isNotBlank();

        // SSE 正常收尾：human_escalation → run_completed → complete（绝不 error）。
        List<CapturedEvent> escalationEvents = appended.stream()
                .filter(event -> "human_escalation".equals(event.type()))
                .toList();
        assertThat(escalationEvents).hasSize(1);
        assertThat(escalationEvents.get(0).data()).containsEntry("code",
                AfterSalesEscalationPolicyService.CODE_UNSUPPORTED_ISSUE_TYPE);
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        int humanEscalationIndex = appended.indexOf(escalationEvents.get(0));
        int runCompletedIndex = appended.indexOf(appended.stream()
                .filter(event -> "run_completed".equals(event.type())).findFirst().orElseThrow());
        assertThat(humanEscalationIndex).isLessThan(runCompletedIndex);
        verify(eventService).complete("run-1");

        // finalAnswer：HUMAN_ESCALATION / requiresApproval false / 结构化升级原因。
        Map<String, Object> runCompletedData = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .map(CapturedEvent::data)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompletedData.get("finalAnswer");
        assertThat(finalAnswer).containsEntry("decisionRoute", "HUMAN_ESCALATION");
        assertThat(finalAnswer).containsEntry("requiresApproval", false);
        assertThat(finalAnswer).doesNotContainKey("proposalId");
        assertThat(finalAnswer.get("escalationReason"))
                .isInstanceOf(AfterSalesTypes.EscalationReason.class);
        AfterSalesTypes.EscalationReason escalationReason =
                (AfterSalesTypes.EscalationReason) finalAnswer.get("escalationReason");
        assertThat(escalationReason.code())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_UNSUPPORTED_ISSUE_TYPE);
        assertThat(escalationReason.category())
                .isEqualTo(AfterSalesEscalationPolicyService.CATEGORY_ISSUE_UNSUPPORTED);
        assertThat(escalationReason.evidenceIds()).isEqualTo(List.of());
    }

    @Test
    void highValueOrderEscalatesRightAfterOrderWithoutProposal() throws Exception {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        // 补偿评估路线 + 高价值演示订单 O-MY-2001（MYR 1,833 > MYR 1,500 阈值）。
        doAnswer(invocation -> {
            AfterSalesAgentState state = invocation.getArgument(1);
            state.setOrder(new AfterSalesTypes.OrderSnapshot(
                    "O-MY-2001", "sea_my_001", "shopify", "MY", "MYR", "MY",
                    new BigDecimal("1833.00"), true, "ready_to_ship", 4, "SF-MY-2001"));
            state.getEvidenceIds().add("order:O-MY-2001:v1");
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of("order:O-MY-2001:v1"));
        }).when(toolExecutor).execute(anyString(), any());

        service.run("run-1", "ticket-1");

        // ORDER 收集后立即升级：SHIPMENT/POLICY/补偿/方案绝不执行。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason()).isEqualTo(AfterSalesEscalationPolicyService.CODE_HIGH_VALUE_ORDER);
        assertThat(savedRun.getStepCount()).isEqualTo(1);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        AfterSalesTicketEntity savedTicket = ticketCaptor.getAllValues().stream()
                .filter(item -> item.getStatus() == AfterSalesTypes.TicketStatus.ESCALATED)
                .findFirst()
                .orElseThrow();
        assertThat(savedTicket.getEscalationReasonJson()).isNotBlank();
        // 升级原因携带触发时点已收集的证据 ID。
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedReason = (Map<String, Object>) objectMapper.readValue(
                savedTicket.getEscalationReasonJson(), Map.class);
        assertThat(persistedReason.get("evidenceIds")).isEqualTo(List.of("order:O-MY-2001:v1"));
    }

    @Test
    void evidenceConflictEscalatesBeforeAnyDecision() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        doAnswer(invocation -> {
            AfterSalesAgentState state = invocation.getArgument(1);
            if (AfterSalesToolExecutor.GET_ORDER_DETAIL.equals(invocation.getArgument(0))) {
                state.setOrder(new AfterSalesTypes.OrderSnapshot(
                        "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                        new BigDecimal("1899000.00"), true, "customs_document_required", 10, "SF-VN-5002"));
                state.getEvidenceIds().add("order:O-VN-5002:v1");
            } else {
                // 物流快照运单号与订单快照矛盾：证据不可信，任何决策前必须升级。
                state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                        "SF-VN-9999", "customs_document_required", Instant.now(), 10, 0, List.of()));
                state.getEvidenceIds().add("shipment:O-VN-5002:conflict");
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of());
        }).when(toolExecutor).execute(anyString(), any());

        service.run("run-1", "ticket-1");

        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason()).isEqualTo(AfterSalesEscalationPolicyService.CODE_EVIDENCE_CONFLICT);
    }

    @Test
    void carrierInvestigationWithinSlaCompletesWaitingExternal() {
        // O-VN-5003 演示：承运商调查 2 天（≤ 7 天 SLA）→ WAITING_EXTERNAL，不失败、不升级、
        // 不查政策、不建方案；SSE 顺序 waiting_external → run_completed → complete。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(lostIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
                case AfterSalesToolExecutor.GET_ORDER_DETAIL -> {
                    state.setOrder(new AfterSalesTypes.OrderSnapshot(
                            "O-VN-5003", "sea_vn_003", "shopify", "VN", "VND", "VN",
                            new BigDecimal("459000.00"), true, "customs_document_required", 7, "SF-VN-5003"));
                    state.getEvidenceIds().add("order:O-VN-5003:v1");
                }
                case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> {
                    state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                            "SF-VN-5003", "customs_document_required", Instant.now(), 2, 0, List.of()));
                    state.getEvidenceIds().add("shipment:O-VN-5003:v1");
                }
                case AfterSalesToolExecutor.GET_CARRIER_CASE -> {
                    state.setCarrierCase(new AfterSalesTypes.CarrierCaseSnapshot(
                            "carrier-case:CC-VN-5003:v1", "CC-VN-5003", "SF Express", "OPEN",
                            Instant.now().minus(2, ChronoUnit.DAYS), Instant.now().minus(2, ChronoUnit.DAYS),
                            "UNDER_INVESTIGATION", "carrier investigation open"));
                    state.getEvidenceIds().add("carrier-case:CC-VN-5003:v1");
                }
                default -> throw new IllegalStateException("UNEXPECTED_ACTION");
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of());
        }).when(toolExecutor).execute(anyString(), any());

        service.run("run-1", "ticket-1");

        // 只收集到 CARRIER_CASE（3 步）：不查政策、不计算补偿、不建方案、不失败。
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));

        // 终态：run WAITING_EXTERNAL / stopReason CARRIER_INVESTIGATION_ACTIVE / ticket WAITING_EXTERNAL。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "WAITING_EXTERNAL".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WAITING_EXTERNAL run not saved; captured: "
                        + runCaptor.getAllValues().stream().map(AfterSalesRunEntity::getStatus).toList()));
        assertThat(savedRun.getStopReason())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_ACTIVE);
        assertThat(savedRun.getStepCount()).isEqualTo(3);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        AfterSalesTicketEntity savedTicket = ticketCaptor.getAllValues().stream()
                .filter(item -> item.getStatus() == AfterSalesTypes.TicketStatus.WAITING_EXTERNAL)
                .findFirst()
                .orElseThrow();
        assertThat(savedTicket.getEscalationReasonJson()).isNotBlank();

        // SSE 正常收尾：waiting_external 先于 run_completed，且调用 complete 关闭流。
        List<CapturedEvent> waitingEvents = appended.stream()
                .filter(event -> "waiting_external".equals(event.type()))
                .toList();
        assertThat(waitingEvents).hasSize(1);
        assertThat(waitingEvents.get(0).data()).containsEntry("code",
                AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_ACTIVE);
        CapturedEvent runCompleted = appended.stream()
                .filter(event -> "run_completed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(appended.indexOf(waitingEvents.get(0))).isLessThan(appended.indexOf(runCompleted));
        verify(eventService).complete("run-1");
        // finalAnswer：外部等待原因结构化输出，无 proposal。
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswer = (Map<String, Object>) runCompleted.data().get("finalAnswer");
        assertThat(finalAnswer).containsEntry("requiresApproval", false);
        assertThat(finalAnswer).doesNotContainKey("proposalId");
        assertThat(finalAnswer.get("externalWait")).isNotNull();
    }

    @Test
    void staleCarrierInvestigationEscalatesInsteadOfPolicyLookup() {
        // 承运商调查 10 天（> 7 天 SLA）→ CARRIER_INVESTIGATION_STALE 升级，绝不继续查政策。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        AfterSalesToolExecutor toolExecutor = mock(AfterSalesToolExecutor.class);
        AfterSalesIntakeService intakeService = mock(AfterSalesIntakeService.class);
        when(intakeService.classify(anyString())).thenReturn(lostIntake());
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

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
                case AfterSalesToolExecutor.GET_ORDER_DETAIL -> {
                    // O-ID-4001 演示：印尼订单，承运商调查 10 天未关闭。
                    state.setOrder(new AfterSalesTypes.OrderSnapshot(
                            "O-ID-4001", "sea_id_001", "shopee", "ID", "IDR", "ID",
                            new BigDecimal("2410000.00"), true, "cross_border_shipping", 8, "SF-ID-4001"));
                    state.getEvidenceIds().add("order:O-ID-4001:v1");
                }
                case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> {
                    state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                            "SF-ID-4001", "cross_border_shipping", Instant.now(), 10, 2, List.of()));
                    state.getEvidenceIds().add("shipment:O-ID-4001:v1");
                }
                case AfterSalesToolExecutor.GET_CARRIER_CASE -> {
                    state.setCarrierCase(new AfterSalesTypes.CarrierCaseSnapshot(
                            "carrier-case:CC-ID-4001:v1", "CC-ID-4001", "SF Express", "OPEN",
                            Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(10, ChronoUnit.DAYS),
                            "UNDER_INVESTIGATION", "carrier investigation open"));
                    state.getEvidenceIds().add("carrier-case:CC-ID-4001:v1");
                }
                default -> throw new IllegalStateException("UNEXPECTED_ACTION");
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(), List.of());
        }).when(toolExecutor).execute(anyString(), any());

        service.run("run-1", "ticket-1");

        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CALCULATE_COMPENSATION), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL), any());
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_STALE);
    }

    @Test
    void policyNotFoundEscalatesInsteadOfFailing() {
        // 政策查找无适用版本（PolicyNotFoundException）：以 POLICY_NOT_COVERED 升级，
        // 绝不走 error 失败分支（run FAILED 不可达）。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);
        doAnswer(invocation -> {
            throw new PolicyNotFoundException("KR", "SHIPMENT_DELAY", Instant.now());
        }).when(toolExecutor).execute(eq(AfterSalesToolExecutor.SEARCH_POLICY), any());

        service.run("run-1", "ticket-1");

        // 升级而非失败：无 error 事件，run ESCALATED / POLICY_NOT_COVERED / ticket ESCALATED。
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        assertThat(appended).anyMatch(event -> "run_completed".equals(event.type()));
        verify(eventService).complete("run-1");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues().stream()
                .map(AfterSalesRunEntity::getStatus)
                .toList()).contains("ESCALATED");
        assertThat(runCaptor.getAllValues().stream()
                .map(AfterSalesRunEntity::getStatus)
                .toList()).doesNotContain("FAILED");
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_POLICY_NOT_COVERED);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.ESCALATED);
        // 升级原因已持久化到工单。
        AfterSalesTicketEntity savedTicket = ticketCaptor.getAllValues().stream()
                .filter(item -> item.getStatus() == AfterSalesTypes.TicketStatus.ESCALATED)
                .findFirst()
                .orElseThrow();
        assertThat(savedTicket.getEscalationReasonJson()).contains(AfterSalesEscalationPolicyService.CODE_POLICY_NOT_COVERED);
    }

    @Test
    void twoConsecutiveTrueDegradedPlansEscalatePlannerFallback() {
        // LLM 规划器两次真实降级（模型调用异常 → LLM_ERROR 兜底）→ 第二个降级周期后立即
        // 转人工升级 PLANNER_CONSECUTIVE_FALLBACK；第一个降级周期的工具照常执行。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService(), failingLlmPlannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L, 9);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // 周期 1 的 ORDER 降级工具照常执行；周期 2 降级后升级，SHIPMENT 绝不执行。
        verify(toolExecutor, times(1)).execute(eq(AfterSalesToolExecutor.GET_ORDER_DETAIL), any());
        verify(toolExecutor, never()).execute(eq(AfterSalesToolExecutor.GET_SHIPMENT_TRACE), any());
        // 两个 planning_fallback 事件（LLM_ERROR）。
        List<CapturedEvent> fallbacks = appended.stream()
                .filter(event -> "planning_fallback".equals(event.type()))
                .toList();
        assertThat(fallbacks).hasSize(2);
        fallbacks.forEach(event -> assertThat(event.data()).containsEntry("fallbackReason", "LLM_ERROR"));
        // 终态：run ESCALATED / PLANNER_CONSECUTIVE_FALLBACK / ticket ESCALATED，无 error。
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(appended).anyMatch(event -> "human_escalation".equals(event.type()));
        verify(eventService).complete("run-1");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "ESCALATED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_PLANNER_CONSECUTIVE_FALLBACK);
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.ESCALATED);
    }

    @Test
    void rulesModePlanningCyclesAreNeverCountedAsFallback() {
        // RULES 模式是主动配置、永不视为降级：全部 4 个 RULES 规划周期后工单正常完成，
        // 绝无 PLANNER_CONSECUTIVE_FALLBACK 升级。
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
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
                attachmentRepository, intakeService, plannerService(), routeResolver(),
                new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.trustedArguments(anyString(), any())).thenReturn(Map.of());
        stubToolExecutor(toolExecutor);

        service.run("run-1", "ticket-1");

        // RULES 模式 4 个规划周期全部完成：COMPLETED / ACTION_PROPOSAL_CREATED，无升级事件。
        assertThat(appended).noneMatch(event -> "human_escalation".equals(event.type()));
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(savedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(savedRun.getStepCount()).isEqualTo(5);
    }

    /** LLM 模式 Planner 测试替身：模型调用抛异常（真实降级 → LLM_ERROR 兜底）。 */
    private AfterSalesEvidencePlannerService failingLlmPlannerService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "LLM", 1000, "test-key-123") {
            @Override
            protected String callModel(String prompt) {
                throw new RuntimeException("upstream model down");
            }
        };
    }

    /** 构造 WAITING_CUSTOMER 父 run 的可恢复快照 JSON（与生产同构：JavaTimeModule 序列化）。 */
    private String writeResumeStateJson(String parentRunId) {
        try {
            return objectMapper.writeValueAsString(new AfterSalesTypes.ResumeState(
                    parentRunId,
                    new AfterSalesTypes.OrderSnapshot(
                            "O-SG-1001", "sea_sg_001", "shopify", "SG", "SGD", "SG",
                            new BigDecimal("588.00"), true, "delivered", 7, "SF-SG-1001"),
                    new AfterSalesTypes.DeliverySnapshot(
                            "delivery:O-SG-1001:2026-08-10", "DL-SG-1001", "SF-SG-1001",
                            Instant.parse("2026-08-10T04:00:00Z"), "Singapore SG",
                            "RECEIVED", "DELIVERED", "SIGNATURE"),
                    damagedIntake(),
                    DecisionRoute.COMPENSATION_EVALUATION,
                    List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY"),
                    List.of("order:O-SG-1001:v1", "delivery:O-SG-1001:2026-08-10")));
        } catch (Exception error) {
            throw new AssertionError("resumeStateJson serialization failed", error);
        }
    }

    /**
     * 与既有测试相同的工具替身：按动作写入状态，返回 stub 结果（含新证据图工具）。
     * 与生产 AfterSalesToolExecutor 一致：取证动作同时把稳定证据 ID 写入 state 并在
     * ToolResult 中返回（补偿计算/方案创建不产生证据 ID）。
     */
    private void stubToolExecutor(AfterSalesToolExecutor toolExecutor) {
        doAnswer(invocation -> {
            AfterSalesAgentState state = invocation.getArgument(1);
            String evidenceId = null;
            switch ((String) invocation.getArgument(0)) {
                case AfterSalesToolExecutor.GET_ORDER_DETAIL -> {
                    state.setOrder(new AfterSalesTypes.OrderSnapshot(
                            "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                            new BigDecimal("1899000.00"), true, "customs_document_required", 10, "SF-VN-5002"));
                    evidenceId = "order:" + state.getOrder().orderId() + ":v1";
                }
                case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> {
                    state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                            "SF-VN-5002", "customs_document_required", Instant.now(), 10, 0, List.of()));
                    evidenceId = "shipment:" + state.getOrder().orderId() + ":"
                            + state.getShipment().lastUpdatedAt().toString().substring(0, 10);
                }
                case AfterSalesToolExecutor.GET_CARRIER_CASE -> {
                    state.setCarrierCase(
                            new AfterSalesTypes.CarrierCaseSnapshot(
                                    "carrier-case:CC-VN-5002:v1", "CC-VN-5002", "SF Express", "CLOSED",
                                    Instant.now(), Instant.now(), "LOST_CONFIRMED", "carrier confirmed lost"));
                    evidenceId = state.getCarrierCase().evidenceId();
                }
                case AfterSalesToolExecutor.GET_DELIVERY_PROOF -> {
                    // 与通用订单 O-VN-5002 内部一致（运单号 SF-VN-5002）：EVIDENCE_CONFLICT
                    // 升级政策要求在场快照运单号唯一，交付快照不得携带另一订单的 O-SG/SF-SG 数据。
                    state.setDelivery(
                            new AfterSalesTypes.DeliverySnapshot(
                                    "delivery:O-VN-5002:2026-08-10", "DL-VN-5002", "SF-VN-5002", Instant.now(),
                                    "Ho Chi Minh City, VN", "RECEIVED", "DELIVERED", "SIGNATURE"));
                    evidenceId = state.getDelivery().evidenceId();
                }
                case AfterSalesToolExecutor.GET_DAMAGE_PHOTO -> {
                    state.setDamagePhoto(
                            new AfterSalesTypes.DamagePhotoSnapshot(
                                    "damage-photo:DP-SG-1001-01:v1", "DP-SG-1001-01", Instant.now(),
                                    "image/jpeg", 1080, 1440, 1_240_000L, "VERIFIED", "confirmed", "sha256:abc"));
                    evidenceId = state.getDamagePhoto().evidenceId();
                }
                case AfterSalesToolExecutor.GET_PRODUCT -> {
                    state.setProduct(new AfterSalesTypes.ProductSnapshot(
                            "product:P001:v1", "P001", "Anker 140W GaN Charger", "accessory",
                            new BigDecimal("89.00"), "SGD", "Anker", true));
                    evidenceId = state.getProduct().evidenceId();
                }
                case AfterSalesToolExecutor.SEARCH_POLICY -> {
                    state.setPolicy(new AfterSalesTypes.PolicyEvidence(
                            "policy:VN_SHIPMENT_DELAY:v3#section-4.2", "VN_SHIPMENT_DELAY", "v3", "VN",
                            "SHIPMENT_DELAY", Instant.now(), 7, new BigDecimal("0.10"),
                            new BigDecimal("150000.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon"));
                    evidenceId = state.getPolicy().evidenceId();
                }
                case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> state.setCompensation(
                        new AfterSalesTypes.CompensationResult(true, "DELAY_COMPENSATION_COUPON",
                                new BigDecimal("150000.00"), "VND", "SHIPMENT_INACTIVE_POLICY_MATCHED"));
                case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> state.setProposalId("proposal-1");
                default -> throw new IllegalStateException("UNEXPECTED_ACTION");
            }
            if (evidenceId != null) {
                state.getEvidenceIds().add(evidenceId);
            }
            return new AfterSalesTypes.ToolResult("stubbed", Map.of(),
                    evidenceId == null ? List.of() : List.of(evidenceId));
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

    /** LOST_IN_TRANSIT 补偿评估路线的 Intake 分类（含 REQUEST_REFUND）。 */
    private AfterSalesTypes.IntakeResult lostIntake() {
        return new AfterSalesTypes.IntakeResult(
                "LOST_IN_TRANSIT", List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY"), "RULE_FALLBACK", "RULES_MODE", 0L);
    }

    /** DAMAGED_ITEM 补偿评估路线的 Intake 分类（含 REQUEST_REFUND）。 */
    private AfterSalesTypes.IntakeResult damagedIntake() {
        return new AfterSalesTypes.IntakeResult(
                "DAMAGED_ITEM", List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), "LOW", Map.of(), List.of(),
                List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY"),
                "RULE_FALLBACK", "RULES_MODE", 0L);
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
