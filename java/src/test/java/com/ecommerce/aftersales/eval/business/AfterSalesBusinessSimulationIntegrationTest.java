package com.ecommerce.aftersales.eval.business;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEventEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunEventRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.ecommerce.aftersales.repository.TicketMessageRepository;
import com.ecommerce.aftersales.service.AfterSalesAgentLoopService;
import com.ecommerce.aftersales.service.AfterSalesEscalationPolicyService;
import com.ecommerce.aftersales.service.AfterSalesIntakeService;
import com.ecommerce.aftersales.service.AfterSalesService;
import com.ecommerce.aftersales.service.AfterSalesToolExecutor;
import com.ecommerce.aftersales.service.DecisionRouteResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.AgentCaseResult;
import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.AgentRunDetail;
import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.BaselineCaseResult;
import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.BusinessSimulationReport;
import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.SimulationScenario;
import static com.ecommerce.aftersales.eval.business.BusinessSimulationTypes.SystemMetrics;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:after_sales_business_sim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.data.redis.repositories.enabled=false",
        "agent.aftersales.intake.mode=RULES",
        "agent.aftersales.planner.mode=RULES",
        "agent.aftersales.demo-step-delay-ms=0",
        "agent.aftersales.retry-scan-ms=3600000"
})
class AfterSalesBusinessSimulationIntegrationTest {

    private static final Set<String> SAFE_TERMINALS = Set.of(
            "RESOLVED", "PENDING_APPROVAL", "WAITING_CUSTOMER", "WAITING_EXTERNAL", "ESCALATED");
    private static final Set<String> TERMINAL_EVENTS = Set.of(
            "decision_completed", "request_more_info", "waiting_external", "human_escalation", "error");

    @Autowired private AfterSalesAgentLoopService loopService;
    @Autowired private AfterSalesService afterSalesService;
    @Autowired private AfterSalesToolExecutor toolExecutor;
    @Autowired private AfterSalesEscalationPolicyService escalationPolicy;
    @Autowired private AfterSalesIntakeService intakeService;
    @Autowired private DecisionRouteResolver routeResolver;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private AfterSalesTicketRepository ticketRepository;
    @Autowired private AfterSalesRunRepository runRepository;
    @Autowired private AfterSalesRunEventRepository eventRepository;
    @Autowired private ActionProposalRepository proposalRepository;
    @Autowired private ApprovalRecordRepository approvalRepository;
    @Autowired private ExecutionJobRepository executionRepository;
    @Autowired private TicketMessageRepository messageRepository;
    @Autowired private TicketAttachmentRepository attachmentRepository;

    @BeforeEach
    void clearSimulationData() {
        executionRepository.deleteAll();
        approvalRepository.deleteAll();
        proposalRepository.deleteAll();
        eventRepository.deleteAll();
        attachmentRepository.deleteAll();
        messageRepository.deleteAll();
        runRepository.deleteAll();
        ticketRepository.deleteAll();
    }

    @Test
    void agentUsesFewerToolsThanFixedWorkflowAcrossSixBusinessPaths() throws Exception {
        List<SimulationScenario> scenarios = List.of(
                new SimulationScenario("S1", "O-VN-5002", "请查询物流状态",
                        "RESOLVED", "ANSWER_DELIVERED", false),
                new SimulationScenario("S2", "O-VN-5002", "物流延迟，请补偿",
                        "PENDING_APPROVAL", "ACTION_PROPOSAL_CREATED", false),
                new SimulationScenario("S3", "O-VN-5003", "物流延迟，请补偿",
                        "RESOLVED", "NO_ACTION_REQUIRED", false),
                new SimulationScenario("S4", "O-VN-5003", "包裹疑似丢失，请退款",
                        "WAITING_EXTERNAL", "CARRIER_INVESTIGATION_ACTIVE", false),
                new SimulationScenario("S5", "O-SG-1001", "收到的商品破损，请补偿",
                        "PENDING_APPROVAL", "ACTION_PROPOSAL_CREATED", true),
                new SimulationScenario("S6", "O-MY-2001", "物流延迟，请补偿",
                        "ESCALATED", "HIGH_VALUE_ORDER", false));

        List<AgentCaseResult> agentCases = new ArrayList<>();
        for (SimulationScenario scenario : scenarios) {
            agentCases.add(runAgentScenario(scenario));
        }

        FixedWorkflowBaseline baseline = new FixedWorkflowBaseline(
                intakeService, routeResolver, toolExecutor, escalationPolicy, attachmentRepository, objectMapper);
        List<BaselineCaseResult> baselineCases = new ArrayList<>();
        for (SimulationScenario scenario : scenarios) {
            baselineCases.add(runBaselineScenario(baseline, scenario));
        }

        assertExpectedPaths(scenarios, agentCases);
        assertResumePath(agentCases.stream().filter(item -> item.scenarioId().equals("S5")).findFirst().orElseThrow());
        baselineCases.forEach(this::assertFixedReadSequence);

        SystemMetrics agentMetrics = agentMetrics(agentCases);
        SystemMetrics baselineMetrics = baselineMetrics(baselineCases);
        assertThat(agentMetrics.taskCompletionRate()).isEqualTo(1.0);
        assertThat(baselineMetrics.taskCompletionRate()).isEqualTo(1.0);
        assertThat(agentMetrics.policyViolationRate()).isZero();
        assertThat(baselineMetrics.policyViolationRate()).isZero();
        assertThat(agentMetrics.averageToolCallsPerCase())
                .isLessThan(baselineMetrics.averageToolCallsPerCase());
        assertThat(agentMetrics.averageHandlingStepsPerCase())
                .isLessThan(baselineMetrics.averageHandlingStepsPerCase());

        BusinessSimulationReport report = new BusinessSimulationReport(
                "After-Sales Agent vs Fixed Workflow - Business Simulation",
                Instant.now().toString(),
                Map.of(
                        "scope", "Deterministic business simulation; not production traffic.",
                        "aht", "No AHT minutes are claimed or estimated."),
                agentMetrics,
                baselineMetrics,
                List.copyOf(agentCases),
                List.copyOf(baselineCases),
                Map.of(
                        "toolCalls", "Agent uses persisted tool_started events; baseline counts every real attempt.",
                        "handlingSteps", "One intake/resume transition plus tool attempts plus one terminal decision per run.",
                        "policyViolation", "Derived from terminal/proposal consistency and route-required persisted evidence."));
        Path reportDirectory = Path.of("target", "after-sales-business-simulation");
        Files.createDirectories(reportDirectory);
        Path json = reportDirectory.resolve("business-simulation-report.json");
        Path markdown = reportDirectory.resolve("business-simulation-report.md");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
        Files.writeString(markdown, markdown(report));
        assertThat(json).exists();
        assertThat(markdown).exists();
        assertThat(Files.readString(markdown))
                .contains("Business Simulation", "Agent", "Fixed Workflow", "No AHT minutes");
    }

    private AgentCaseResult runAgentScenario(SimulationScenario scenario) throws Exception {
        String ticketId = "bs-agent-" + scenario.id();
        String firstRunId = "bs-agent-run-" + scenario.id() + "-1";
        AfterSalesTicketEntity ticket = ticketRepository.save(AfterSalesTicketEntity.builder()
                .id(ticketId)
                .ticketNo("AS-BS-AGENT-" + scenario.id())
                .orderId(scenario.orderId())
                .issueType(AfterSalesTypes.IntakeResult.SHIPMENT_DELAY)
                .customerMessage(scenario.customerMessage())
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .currentRunId(firstRunId)
                .createdAt(Instant.now())
                .build());
        runRepository.save(AfterSalesRunEntity.builder()
                .id(firstRunId)
                .ticketId(ticketId)
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build());
        loopService.run(firstRunId, ticketId);

        List<AgentRunDetail> details = new ArrayList<>();
        details.add(runDetail(firstRunId));
        String finalRunId = firstRunId;
        if (scenario.resumePhase()) {
            AfterSalesRunEntity parent = runRepository.findById(firstRunId).orElseThrow();
            assertThat(parent.getStatus()).isEqualTo("WAITING_CUSTOMER");
            assertThat(parent.getStopReason()).isEqualTo("CUSTOMER_INFO_REQUIRED");
            Map<String, Object> appendResult = afterSalesService.appendCustomerMessage(
                    ticketId,
                    "I have uploaded the requested damage photo.",
                    List.of(new AfterSalesService.AttachmentInput(
                            "damage.jpg", "image/jpeg", null,
                            "objects/business-simulation/damage.jpg", "sha256:business-simulation",
                            Map.of("width", 1080, "height", 1440, "sizeBytes", 1240000))),
                    true);
            finalRunId = String.valueOf(appendResult.get("runId"));
            markLatestAttachmentVerified(ticketId);
            assertThat(runRepository.claimReady(finalRunId, Instant.now())).isEqualTo(1);
            loopService.run(finalRunId, ticketId);
            details.add(runDetail(finalRunId));
            assertThat(runRepository.findById(finalRunId).orElseThrow().getParentRunId()).isEqualTo(firstRunId);
        }

        AfterSalesTicketEntity finalTicket = ticketRepository.findById(ticketId).orElseThrow();
        AfterSalesRunEntity finalRun = runRepository.findById(finalRunId).orElseThrow();
        Optional<ActionProposalEntity> proposal = proposalRepository.findTopByTicketIdOrderByCreatedAtDesc(ticketId);
        int toolCalls = details.stream().mapToInt(item -> item.toolCalls().size()).sum();
        int handlingSteps = details.stream().mapToInt(AgentRunDetail::handlingSteps).sum();
        String terminalStatus = finalTicket.getStatus().name();
        boolean violation = policyViolation(ticketId, scenario.customerMessage(), terminalStatus, proposal);
        return new AgentCaseResult(
                scenario.id(), scenario.expectedTerminalStatus(), scenario.expectedStopReason(),
                terminalStatus, finalRun.getStopReason(), List.copyOf(details), toolCalls, handlingSteps,
                proposal.isPresent(), violation, SAFE_TERMINALS.contains(terminalStatus),
                "RESOLVED".equals(terminalStatus), "ESCALATED".equals(terminalStatus));
    }

    private BaselineCaseResult runBaselineScenario(FixedWorkflowBaseline baseline, SimulationScenario scenario) {
        List<FixedWorkflowBaseline.BaselineRunResult> runs = new ArrayList<>();
        runs.add(baseline.run(scenario.id(), scenario.orderId(), scenario.customerMessage(), false));
        if (scenario.resumePhase()) {
            runs.add(baseline.run(scenario.id(), scenario.orderId(), scenario.customerMessage(), true));
        }
        FixedWorkflowBaseline.BaselineRunResult last = runs.get(runs.size() - 1);
        int tools = runs.stream().mapToInt(FixedWorkflowBaseline.BaselineRunResult::toolCallCount).sum();
        int steps = runs.stream().mapToInt(FixedWorkflowBaseline.BaselineRunResult::handlingSteps).sum();
        boolean proposal = runs.stream().anyMatch(FixedWorkflowBaseline.BaselineRunResult::proposalCreated);
        boolean violation = runs.stream().anyMatch(FixedWorkflowBaseline.BaselineRunResult::policyViolation);
        return new BaselineCaseResult(
                scenario.id(), last.terminalStatus(), last.stopReason(), List.copyOf(runs), tools, steps,
                proposal, violation, SAFE_TERMINALS.contains(last.terminalStatus()),
                "RESOLVED".equals(last.terminalStatus()), "ESCALATED".equals(last.terminalStatus()));
    }

    private AgentRunDetail runDetail(String runId) {
        AfterSalesRunEntity run = runRepository.findById(runId).orElseThrow();
        List<AfterSalesRunEventEntity> events = eventRepository.findByRunIdOrderBySequenceAsc(runId);
        List<String> tools = events.stream()
                .filter(event -> "tool_started".equals(event.getType()))
                .map(event -> String.valueOf(readMap(event.getDataJson()).get("action")))
                .toList();
        String transition = events.stream()
                .map(AfterSalesRunEventEntity::getType)
                .filter(type -> type.equals("intake_completed") || type.equals("run_resumed"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("transition event missing for " + runId));
        String terminal = events.stream()
                .map(AfterSalesRunEventEntity::getType)
                .filter(TERMINAL_EVENTS::contains)
                .findFirst()
                .orElseThrow(() -> new AssertionError("terminal event missing for " + runId));
        return new AgentRunDetail(
                runId, run.getParentRunId(), run.getParentRunId() != null, run.getStatus(), run.getStopReason(),
                tools, transition, terminal, 1 + tools.size() + 1);
    }

    private boolean policyViolation(
            String ticketId,
            String customerMessage,
            String terminalStatus,
            Optional<ActionProposalEntity> proposal) {
        boolean pendingApproval = "PENDING_APPROVAL".equals(terminalStatus);
        if (pendingApproval != proposal.isPresent()) {
            return true;
        }
        if (proposal.isEmpty()) {
            return false;
        }
        if (terminalStatus.startsWith("WAITING") || "ESCALATED".equals(terminalStatus)) {
            return true;
        }
        AfterSalesTypes.IntakeResult intake = intakeService.classify(customerMessage);
        List<String> required = routeResolver.resolve(intake).requiredEvidence();
        ActionProposalEntity saved = proposal.orElseThrow();
        List<String> evidenceIds = FixedWorkflowBaseline.readStringList(saved.getEvidenceIdsJson());
        boolean complete = required.stream().allMatch(name -> evidenceIds.stream()
                .anyMatch(id -> id.startsWith(FixedWorkflowBaseline.evidencePrefix(name))));
        return !complete || saved.getPolicyVersion() == null || saved.getPolicyVersion().isBlank()
                || !ticketId.equals(saved.getTicketId());
    }

    private void markLatestAttachmentVerified(String ticketId) throws Exception {
        TicketAttachmentEntity attachment = attachmentRepository.findByTicketIdOrderByCreatedAtDesc(ticketId)
                .stream().findFirst().orElseThrow();
        Map<String, Object> metadata = new LinkedHashMap<>(readMap(attachment.getMetadataJson()));
        metadata.put(TicketAttachmentEntity.METADATA_REVIEW_STATUS, "VERIFIED");
        metadata.put(TicketAttachmentEntity.METADATA_REVIEW_SUMMARY,
                "Business simulation server review confirmed visible damage.");
        attachment.setMetadataJson(objectMapper.writeValueAsString(metadata));
        attachmentRepository.save(attachment);
    }

    private void assertExpectedPaths(List<SimulationScenario> scenarios, List<AgentCaseResult> results) {
        for (SimulationScenario scenario : scenarios) {
            AgentCaseResult result = results.stream()
                    .filter(item -> item.scenarioId().equals(scenario.id())).findFirst().orElseThrow();
            assertThat(result.terminalStatus()).isEqualTo(scenario.expectedTerminalStatus());
            assertThat(result.stopReason()).isEqualTo(scenario.expectedStopReason());
            assertThat(result.policyViolation()).isFalse();
        }
    }

    private void assertResumePath(AgentCaseResult result) {
        assertThat(result.runs()).hasSize(2);
        AgentRunDetail first = result.runs().get(0);
        AgentRunDetail resumed = result.runs().get(1);
        assertThat(first.status()).isEqualTo("WAITING_CUSTOMER");
        assertThat(first.stopReason()).isEqualTo("CUSTOMER_INFO_REQUIRED");
        assertThat(resumed.parentRunId()).isEqualTo(first.runId());
        assertThat(resumed.transitionEvent()).isEqualTo("run_resumed");
        assertThat(resumed.toolCalls()).doesNotContain(
                AfterSalesToolExecutor.GET_ORDER_DETAIL, AfterSalesToolExecutor.GET_DELIVERY_PROOF);
    }

    private void assertFixedReadSequence(BaselineCaseResult result) {
        for (FixedWorkflowBaseline.BaselineRunResult run : result.runs()) {
            assertThat(run.toolCalls()).hasSizeGreaterThanOrEqualTo(FixedWorkflowBaseline.FIXED_READ_SEQUENCE.size());
            List<String> attemptedReads = run.toolCalls().subList(0, FixedWorkflowBaseline.FIXED_READ_SEQUENCE.size())
                    .stream().map(value -> value.split(":", 2)[0]).toList();
            assertThat(attemptedReads).containsExactlyElementsOf(FixedWorkflowBaseline.FIXED_READ_SEQUENCE);
        }
    }

    private SystemMetrics agentMetrics(List<AgentCaseResult> results) {
        int count = results.size();
        return new SystemMetrics(
                "Agent", count,
                rate(results.stream().filter(AgentCaseResult::taskCompleted).count(), count),
                rate(results.stream().filter(AgentCaseResult::autoResolved).count(), count),
                rate(results.stream().filter(AgentCaseResult::humanEscalated).count(), count),
                results.stream().mapToInt(AgentCaseResult::toolCallCount).average().orElse(0),
                results.stream().mapToInt(AgentCaseResult::handlingSteps).average().orElse(0),
                rate(results.stream().filter(AgentCaseResult::policyViolation).count(), count));
    }

    private SystemMetrics baselineMetrics(List<BaselineCaseResult> results) {
        int count = results.size();
        return new SystemMetrics(
                "Fixed Workflow", count,
                rate(results.stream().filter(BaselineCaseResult::taskCompleted).count(), count),
                rate(results.stream().filter(BaselineCaseResult::autoResolved).count(), count),
                rate(results.stream().filter(BaselineCaseResult::humanEscalated).count(), count),
                results.stream().mapToInt(BaselineCaseResult::toolCallCount).average().orElse(0),
                results.stream().mapToInt(BaselineCaseResult::handlingSteps).average().orElse(0),
                rate(results.stream().filter(BaselineCaseResult::policyViolation).count(), count));
    }

    private static double rate(long numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new AssertionError("Unreadable persisted JSON", error);
        }
    }

    private String markdown(BusinessSimulationReport report) {
        StringBuilder output = new StringBuilder();
        output.append("# After-Sales Business Simulation\n\n")
                .append("Deterministic Business Simulation only. No AHT minutes and no production claim.\n\n")
                .append("| Metric | Agent | Fixed Workflow |\n")
                .append("|---|---:|---:|\n");
        appendMetric(output, "Task Completion Rate", report.agentMetrics().taskCompletionRate(),
                report.baselineMetrics().taskCompletionRate());
        appendMetric(output, "Auto Resolution Rate", report.agentMetrics().autoResolutionRate(),
                report.baselineMetrics().autoResolutionRate());
        appendMetric(output, "Human Escalation Rate", report.agentMetrics().humanEscalationRate(),
                report.baselineMetrics().humanEscalationRate());
        appendMetric(output, "Average Tool Calls / Case", report.agentMetrics().averageToolCallsPerCase(),
                report.baselineMetrics().averageToolCallsPerCase());
        appendMetric(output, "Average Handling Steps", report.agentMetrics().averageHandlingStepsPerCase(),
                report.baselineMetrics().averageHandlingStepsPerCase());
        appendMetric(output, "Policy Violation Rate", report.agentMetrics().policyViolationRate(),
                report.baselineMetrics().policyViolationRate());
        output.append("\n## Cases\n\n| Case | Agent terminal | Tools | Fixed terminal | Tools |\n|---|---|---:|---|---:|\n");
        for (AgentCaseResult agent : report.agentCases()) {
            BaselineCaseResult baseline = report.baselineCases().stream()
                    .filter(item -> item.scenarioId().equals(agent.scenarioId())).findFirst().orElseThrow();
            output.append("| ").append(agent.scenarioId()).append(" | ")
                    .append(agent.terminalStatus()).append(" / ").append(agent.stopReason()).append(" | ")
                    .append(agent.toolCallCount()).append(" | ")
                    .append(baseline.terminalStatus()).append(" / ").append(baseline.stopReason()).append(" | ")
                    .append(baseline.toolCallCount()).append(" |\n");
        }
        return output.toString();
    }

    private static void appendMetric(StringBuilder output, String name, double agent, double baseline) {
        output.append("| ").append(name).append(" | ")
                .append(String.format("%.4f", agent)).append(" | ")
                .append(String.format("%.4f", baseline)).append(" |\n");
    }
}
