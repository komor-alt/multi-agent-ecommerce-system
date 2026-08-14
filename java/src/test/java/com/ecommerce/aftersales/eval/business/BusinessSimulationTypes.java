package com.ecommerce.aftersales.eval.business;

import java.util.List;
import java.util.Map;

/**
 * Shared records for the Phase 4 business simulation
 * (Agent vs. Fixed-Workflow comparison, see AfterSalesBusinessSimulationHarness).
 *
 * Handling-steps definition (identical for both systems, documented in the report):
 * - intake/resume transition: 1 per run (agent: one intake_completed or run_resumed
 *   event; baseline: one intake classification);
 * - every tool call/attempt: 1 per executed or attempted tool (agent: persisted
 *   tool_started events; baseline: each of the fixed 7 read attempts, plus
 *   calculate_compensation / create_action_proposal when the terminal rules reach them);
 * - terminal decision: 1 per run (agent: decision_completed / request_more_info /
 *   waiting_external / human_escalation, or error for FAILED runs; baseline: the
 *   deterministic terminal rule that closed the run).
 * No AHT minutes are reported anywhere in the simulation.
 */
public final class BusinessSimulationTypes {

    private BusinessSimulationTypes() {
    }

    /**
     * One deterministic simulation scenario. Expected terminal fields are the
     * scenario's contract (asserted by the JUnit test); the harness never
     * hard-codes metric results.
     */
    public record SimulationScenario(
            String id,
            String orderId,
            String customerMessage,
            /** Expected final ticket status of the Agent path (e.g. RESOLVED / PENDING_APPROVAL). */
            String expectedTerminalStatus,
            /** Expected stopReason of the Agent path's final run (e.g. ANSWER_DELIVERED / ACTION_PROPOSAL_CREATED). */
            String expectedStopReason,
            /** true for S5: first run ends WAITING_CUSTOMER, then a photo attachment resumes a child run. */
            boolean resumePhase
    ) {
    }

    /** One persisted agent run (a resumed child run carries parentRunId). */
    public record AgentRunDetail(
            String runId,
            /** null for the first run; the resumed parent run id for a child run. */
            String parentRunId,
            boolean resumed,
            String status,
            String stopReason,
            /** Tool actions observed in persisted tool_started events, in sequence order. */
            List<String> toolCalls,
            /** "intake_completed" for fresh runs, "run_resumed" for resumed runs. */
            String transitionEvent,
            /** decision_completed | request_more_info | waiting_external | human_escalation | error. */
            String terminalEvent,
            /** 1 (transition) + tool call count + 1 (terminal decision). */
            int handlingSteps
    ) {
    }

    /** Agent-side result of one scenario (S5 aggregates both runs). */
    public record AgentCaseResult(
            String scenarioId,
            String expectedTerminalStatus,
            String expectedStopReason,
            /** Final ticket status after all phases (S5: final PENDING_APPROVAL). */
            String terminalStatus,
            /** stopReason of the final run. */
            String stopReason,
            List<AgentRunDetail> runs,
            int toolCallCount,
            int handlingSteps,
            boolean proposalCreated,
            boolean policyViolation,
            boolean taskCompleted,
            boolean autoResolved,
            boolean humanEscalated
    ) {
    }

    /** Baseline-side result of one scenario (S5 aggregates the two fixed runs). */
    public record BaselineCaseResult(
            String scenarioId,
            /** Terminal status of the final phase (S5: phase-2 run). */
            String terminalStatus,
            String stopReason,
            List<FixedWorkflowBaseline.BaselineRunResult> runs,
            int toolCallCount,
            int handlingSteps,
            boolean proposalCreated,
            boolean policyViolation,
            boolean taskCompleted,
            boolean autoResolved,
            boolean humanEscalated
    ) {
    }

    /** Aggregate metrics for one system. All rates are 0..1. */
    public record SystemMetrics(
            String system,
            int caseCount,
            double taskCompletionRate,
            double autoResolutionRate,
            double humanEscalationRate,
            double averageToolCallsPerCase,
            double averageHandlingStepsPerCase,
            double policyViolationRate
    ) {
    }

    /**
     * The full simulation report. Label and disclaimers make it unmistakable that
     * this is a Business Simulation artifact: no AHT minutes, no production claim.
     */
    public record BusinessSimulationReport(
            String label,
            String generatedAt,
            Map<String, String> disclaimers,
            SystemMetrics agentMetrics,
            SystemMetrics baselineMetrics,
            List<AgentCaseResult> agentCases,
            List<BaselineCaseResult> baselineCases,
            Map<String, String> methodology
    ) {
    }
}
