package com.ecommerce.aftersales.eval.business;

import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.ecommerce.aftersales.service.AfterSalesEscalationPolicyService;
import com.ecommerce.aftersales.service.AfterSalesIntakeService;
import com.ecommerce.aftersales.service.AfterSalesToolExecutor;
import com.ecommerce.aftersales.service.DecisionRouteResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixed-workflow baseline for the business simulation.
 *
 * For every run the baseline ALWAYS attempts all seven read systems in this
 * exact order — get_order_detail, get_shipment_trace, get_carrier_case,
 * get_delivery_proof, get_damage_photo, get_product, search_after_sales_policy —
 * regardless of the classified issue, using the REAL AfterSalesToolExecutor and
 * the trusted AfterSalesAgentState it fills. It intentionally over-fetches:
 * a delay ticket gets the carrier case and delivery proof it never needs, which
 * is exactly the inefficiency the simulation measures.
 *
 * Failure semantics:
 * - A missing damage photo (no image attachment on the ticket) is recorded as
 *   "get_damage_photo:DAMAGE_PHOTO_ATTACHMENT_MISSING" (and its direct
 *   prerequisite consequence "get_product:DAMAGE_PHOTO_REQUIRED") and the
 *   baseline CONTINUES querying the remaining systems.
 * - Any other unexpected failure fails the scenario (terminal FAILED).
 *
 * After all seven reads the baseline applies the SAME AfterSalesEscalationPolicyService
 * and then the deterministic terminal rules, first match wins (documented in the
 * report methodology):
 *   1. unsupported issue (HUMAN_ESCALATION route)          -> ESCALATED / UNSUPPORTED_ISSUE_TYPE
 *   2. high value / evidence conflict / stale investigation -> ESCALATED / policy reason code
 *   3. active carrier investigation (within SLA)           -> WAITING_EXTERNAL / CARRIER_INVESTIGATION_ACTIVE
 *   4. DAMAGED_ITEM with missing damage photo              -> WAITING_CUSTOMER / CUSTOMER_INFO_REQUIRED
 *   5. ANSWER_ONLY                                         -> RESOLVED / ANSWER_DELIVERED
 *   6. COMPENSATION_EVALUATION                             -> calculate_compensation; only when
 *                                                             eligible create_action_proposal ->
 *                                                             PENDING_APPROVAL / ACTION_PROPOSAL_CREATED,
 *                                                             otherwise RESOLVED / NO_ACTION_REQUIRED
 *
 * Handling steps = 1 (intake classification transition) + every tool call/attempt
 * + 1 (terminal decision). No AHT minutes are reported.
 */
public final class FixedWorkflowBaseline {

    /** Fixed evidence sequence: every run attempts all seven read systems in this exact order. */
    public static final List<String> FIXED_READ_SEQUENCE = List.of(
            AfterSalesToolExecutor.GET_ORDER_DETAIL,
            AfterSalesToolExecutor.GET_SHIPMENT_TRACE,
            AfterSalesToolExecutor.GET_CARRIER_CASE,
            AfterSalesToolExecutor.GET_DELIVERY_PROOF,
            AfterSalesToolExecutor.GET_DAMAGE_PHOTO,
            AfterSalesToolExecutor.GET_PRODUCT,
            AfterSalesToolExecutor.SEARCH_POLICY
    );

    /** Known missing-photo conditions the baseline tolerates (recorded, sequence continues). */
    private static final String DAMAGE_PHOTO_ATTACHMENT_MISSING = "DAMAGE_PHOTO_ATTACHMENT_MISSING";
    private static final String DAMAGE_PHOTO_REQUIRED = "DAMAGE_PHOTO_REQUIRED";

    /** Result of one fixed baseline run (S5 executes two runs: no photo, then photo present). */
    public record BaselineRunResult(
            String scenarioId,
            /** Terminal ticket status: RESOLVED / PENDING_APPROVAL / WAITING_CUSTOMER / WAITING_EXTERNAL / ESCALATED / FAILED. */
            String terminalStatus,
            /** Terminal reason (policy code / ANSWER_DELIVERED / NO_ACTION_REQUIRED / ... / failure code). */
            String stopReason,
            /** Every tool call/attempt in sequence order; blocked attempts carry ":<code>". */
            List<String> toolCalls,
            int toolCallCount,
            /** 1 (intake transition) + tool call count + 1 (terminal decision). */
            int handlingSteps,
            boolean proposalCreated,
            boolean policyViolation,
            boolean scenarioFailed,
            List<String> evidenceIds
    ) {
    }

    private final AfterSalesIntakeService intakeService;
    private final DecisionRouteResolver routeResolver;
    private final AfterSalesToolExecutor toolExecutor;
    private final AfterSalesEscalationPolicyService escalationPolicy;
    private final TicketAttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;

    public FixedWorkflowBaseline(
            AfterSalesIntakeService intakeService,
            DecisionRouteResolver routeResolver,
            AfterSalesToolExecutor toolExecutor,
            AfterSalesEscalationPolicyService escalationPolicy,
            TicketAttachmentRepository attachmentRepository,
            ObjectMapper objectMapper) {
        this.intakeService = intakeService;
        this.routeResolver = routeResolver;
        this.toolExecutor = toolExecutor;
        this.escalationPolicy = escalationPolicy;
        this.attachmentRepository = attachmentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs one fixed baseline pass for a scenario. photoPresent=true saves an
     * image attachment (server-side VERIFIED review metadata) on the baseline
     * ticket first, mirroring the agent S5 phase-2 resume data.
     */
    public BaselineRunResult run(
            String scenarioId,
            String orderId,
            String customerMessage,
            boolean photoPresent) {
        return run(scenarioId, orderId, customerMessage, photoPresent, Instant.now());
    }

    public BaselineRunResult run(String scenarioId, String orderId, String customerMessage,
                                 boolean photoPresent, Instant occurredAt) {
        String ticketId = "bs-" + scenarioId + "-baseline";
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id(ticketId)
                .ticketNo("AS-BS-" + scenarioId)
                .orderId(orderId)
                .issueType(AfterSalesTypes.IntakeResult.SHIPMENT_DELAY)
                .customerMessage(customerMessage)
                .status(AfterSalesTypes.TicketStatus.OPEN)
                .createdAt(occurredAt)
                .build();
        if (photoPresent) {
            saveVerifiedPhotoAttachment(ticketId, scenarioId);
        }
        AfterSalesAgentState state = new AfterSalesAgentState("baseline-" + scenarioId, ticket);

        // Real intake classification (RULES mode) + server-side route resolution.
        AfterSalesTypes.IntakeResult intake = intakeService.classify(customerMessage);
        ticket.setIssueType(intake.issueType());
        DecisionRouteResolver.RouteDecision routeDecision = routeResolver.resolve(intake);
        // Trusted intake: requiredEvidence is rebuilt from the server-side route
        // decision, not the untrusted classification suggestion.
        state.setIntake(intake.withRequiredEvidence(routeDecision.requiredEvidence()));
        state.setRoute(routeDecision.route());

        // Fixed 7-read sequence: always attempted, in this exact order, regardless of issue.
        List<String> toolCalls = new ArrayList<>();
        String failure = null;
        for (String tool : FIXED_READ_SEQUENCE) {
            try {
                toolExecutor.execute(tool, state);
                toolCalls.add(tool);
            } catch (IllegalStateException error) {
                String code = error.getMessage();
                if (DAMAGE_PHOTO_ATTACHMENT_MISSING.equals(code) || DAMAGE_PHOTO_REQUIRED.equals(code)) {
                    // Missing damage photo (and its direct prerequisite consequence): recorded
                    // as missing, the sequence CONTINUES with the remaining systems.
                    toolCalls.add(tool + ":" + code);
                } else {
                    // Any other unexpected failure fails the scenario.
                    failure = code;
                    break;
                }
            }
        }
        if (failure != null) {
            return result(scenarioId, "FAILED", failure, toolCalls, state, false, true);
        }

        // Deterministic terminal rules, first match wins (see class javadoc).
        String terminalStatus;
        String stopReason;
        boolean proposalCreated = false;
        if (state.getRoute() == DecisionRoute.HUMAN_ESCALATION) {
            // Rule 1: unsupported issue -> ESCALATED, zero tools beyond the fixed reads.
            terminalStatus = "ESCALATED";
            stopReason = AfterSalesEscalationPolicyService.CODE_UNSUPPORTED_ISSUE_TYPE;
        } else {
            AfterSalesEscalationPolicyService.Assessment assessment = escalationPolicy.assess(state);
            if (assessment.escalation() != null) {
                // Rule 2: high value / conflict / stale investigation -> ESCALATED.
                terminalStatus = "ESCALATED";
                stopReason = assessment.escalation().code();
            } else if (assessment.waiting() != null) {
                // Rule 3: active carrier investigation within SLA -> WAITING_EXTERNAL.
                terminalStatus = "WAITING_EXTERNAL";
                stopReason = AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_ACTIVE;
            } else if (AfterSalesTypes.IntakeResult.DAMAGED_ITEM.equals(ticket.getIssueType())
                    && state.getDamagePhoto() == null) {
                // Rule 4: DAMAGED_ITEM without damage photo -> WAITING_CUSTOMER.
                terminalStatus = "WAITING_CUSTOMER";
                stopReason = "CUSTOMER_INFO_REQUIRED";
            } else if (state.getRoute() == DecisionRoute.ANSWER_ONLY) {
                // Rule 5: tracking-only -> RESOLVED (answer delivered), no compensation tools.
                terminalStatus = "RESOLVED";
                stopReason = "ANSWER_DELIVERED";
            } else if (state.getRoute() == DecisionRoute.COMPENSATION_EVALUATION) {
                // Rule 6: compensation -> real calculate_compensation; only when eligible
                // create_action_proposal -> PENDING_APPROVAL, otherwise RESOLVED.
                toolCalls.add(AfterSalesToolExecutor.CALCULATE_COMPENSATION);
                toolExecutor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
                if (state.getCompensation().eligible()) {
                    toolCalls.add(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL);
                    toolExecutor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state);
                    terminalStatus = "PENDING_APPROVAL";
                    stopReason = "ACTION_PROPOSAL_CREATED";
                    proposalCreated = true;
                } else {
                    terminalStatus = "RESOLVED";
                    stopReason = "NO_ACTION_REQUIRED";
                }
            } else {
                terminalStatus = "FAILED";
                stopReason = "BASELINE_ROUTE_NOT_HANDLED";
            }
        }
        return result(scenarioId, terminalStatus, stopReason, toolCalls, state, proposalCreated, false);
    }

    private BaselineRunResult result(
            String scenarioId,
            String terminalStatus,
            String stopReason,
            List<String> toolCalls,
            AfterSalesAgentState state,
            boolean proposalCreated,
            boolean scenarioFailed) {
        boolean policyViolation = proposalCreated && isWaitOrEscalated(terminalStatus)
                || proposalCreated && !evidenceComplete(state);
        return new BaselineRunResult(
                scenarioId,
                terminalStatus,
                stopReason,
                List.copyOf(toolCalls),
                toolCalls.size(),
                1 + toolCalls.size() + 1,
                proposalCreated,
                policyViolation,
                scenarioFailed,
                List.copyOf(state.getEvidenceIds()));
    }

    /**
     * Whether the state holds the complete route-required evidence chain: every
     * required evidence name must have at least one state evidence ID starting
     * with that name's {@link #evidencePrefix(String)}. A null intake or a null
     * requiredEvidence list is never complete.
     */
    private boolean evidenceComplete(AfterSalesAgentState state) {
        AfterSalesTypes.IntakeResult intake = state.getIntake();
        if (intake == null || intake.requiredEvidence() == null) {
            return false;
        }
        for (String evidenceName : intake.requiredEvidence()) {
            String prefix = evidencePrefix(evidenceName);
            boolean found = state.getEvidenceIds().stream().anyMatch(id -> id.startsWith(prefix));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Policy violation for the baseline: a proposal was created while the terminal
     * state was ESCALATED/WAITING (checked in {@link #result}), or the proposal's
     * persisted evidence chain misses a route-required evidence category. The
     * route-required list comes from the server-side route decision; the evidence
     * chain from the persisted proposal (real repository, same data the agent
     * path is judged on).
     */
    private boolean isWaitOrEscalated(String terminalStatus) {
        return "ESCALATED".equals(terminalStatus)
                || "WAITING_CUSTOMER".equals(terminalStatus)
                || "WAITING_EXTERNAL".equals(terminalStatus);
    }

    /** Photo attachment with server-side VERIFIED review metadata (reviewStatus is a server-held key). */
    private void saveVerifiedPhotoAttachment(String ticketId, String scenarioId) {
        attachmentRepository.save(TicketAttachmentEntity.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(ticketId)
                .messageId("bs-message-" + scenarioId)
                .fileName("damage.jpg")
                .contentType("image/jpeg")
                .storageKey("objects/bs-" + scenarioId + "-damage.jpg")
                .metadataJson("{\"width\":1080,\"height\":1440,\"sizeBytes\":1240000,"
                        + "\"reviewStatus\":\"VERIFIED\","
                        + "\"reviewSummary\":\"Manual review confirmed visible damage matching the reported item.\"}")
                .createdAt(Instant.now())
                .build());
    }

    // Evidence-category prefix mapping shared with the harness violation checks.
    static String evidencePrefix(String evidenceName) {
        return switch (evidenceName) {
            case "CARRIER_CASE" -> "carrier-case:";
            case "DAMAGE_PHOTO" -> "damage-photo:";
            default -> evidenceName.toLowerCase() + ":";
        };
    }

    static List<String> readStringList(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception error) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("BASELINE_SERIALIZATION_FAILED", error);
        }
    }
}
