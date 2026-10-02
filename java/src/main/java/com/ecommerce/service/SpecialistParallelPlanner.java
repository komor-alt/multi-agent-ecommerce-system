package com.ecommerce.service;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.AgentId;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a bounded batch of specialist actions whose declared state access does
 * not conflict. The declarations are a server-side contract; model output can
 * never add an action to the batch.
 */
public class SpecialistParallelPlanner {
    private static final Map<String, StateAccess> ACCESS = accessContracts();

    private final ScenePathEnforcer pathEnforcer;
    private final RecommendationOrchestrationProperties properties;

    public SpecialistParallelPlanner(
            ScenePathEnforcer pathEnforcer,
            RecommendationOrchestrationProperties properties) {
        this.pathEnforcer = pathEnforcer;
        this.properties = properties;
    }

    public List<PlannedSpecialistAction> plan(
            RecommendationPipelineState state,
            List<String> whitelist,
            int remainingToolBudget) {
        if (!properties.isParallelEnabled() || remainingToolBudget < 2
                || properties.getMaxParallelSpecialists() < 2) {
            return List.of();
        }

        AgentId expected = pathEnforcer.expectedNextAgent(state.getRequest().getScene(), state);
        LinkedHashSet<AgentId> orderedAgents = new LinkedHashSet<>();
        if (expected != AgentId.SUPERVISOR) {
            orderedAgents.add(expected);
        }
        orderedAgents.addAll(List.of(AgentId.PROFILE, AgentId.PRODUCT, AgentId.INVENTORY, AgentId.COPY));

        List<PlannedSpecialistAction> selected = new ArrayList<>();
        for (AgentId agent : orderedAgents) {
            List<String> executable = pathEnforcer.executableTools(agent, state.getRequest().getScene(), state);
            String action = executable.stream()
                    .filter(whitelist::contains)
                    .filter(this::parallelEligible)
                    .findFirst()
                    .orElse(null);
            if (action == null || conflicts(action, selected)) {
                continue;
            }
            selected.add(new PlannedSpecialistAction(agent, action));
            int limit = Math.min(properties.getMaxParallelSpecialists(), remainingToolBudget);
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected.size() > 1 ? List.copyOf(selected) : List.of();
    }

    private boolean parallelEligible(String action) {
        if (!properties.isSpeculativeRerankEnabled() && ScenePathEnforcer.RERANK.equals(action)) {
            return false;
        }
        return ACCESS.containsKey(action);
    }

    private boolean conflicts(String action, List<PlannedSpecialistAction> selected) {
        StateAccess candidate = ACCESS.get(action);
        for (PlannedSpecialistAction existing : selected) {
            StateAccess current = ACCESS.get(existing.action());
            if (!disjoint(candidate.writes(), current.reads())
                    || !disjoint(current.writes(), candidate.reads())
                    || !disjoint(candidate.writes(), current.writes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean disjoint(Set<StateResource> left, Set<StateResource> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static Map<String, StateAccess> accessContracts() {
        Map<String, StateAccess> values = new LinkedHashMap<>();
        values.put(ScenePathEnforcer.GET_USER_PROFILE,
                access(set(), set(StateResource.PROFILE)));
        values.put(ScenePathEnforcer.GET_RECENT_ORDERS,
                access(set(), set(StateResource.ORDER_CONTEXT)));
        values.put(ScenePathEnforcer.LOAD_CAMPAIGN_CONSTRAINTS,
                access(set(), set(StateResource.CAMPAIGN_CONSTRAINTS)));
        values.put(ScenePathEnforcer.SEARCH_PRODUCTS,
                access(set(StateResource.PROFILE, StateResource.ORDER_CONTEXT),
                        set(StateResource.RAW_PRODUCTS)));
        values.put(ScenePathEnforcer.CHECK_FULFILLMENT,
                access(set(StateResource.RAW_PRODUCTS, StateResource.CAMPAIGN_CONSTRAINTS),
                        set(StateResource.RAW_PRODUCTS, StateResource.FULFILLMENT)));
        values.put(ScenePathEnforcer.CHECK_INVENTORY,
                access(set(StateResource.RAW_PRODUCTS, StateResource.FULFILLMENT),
                        set(StateResource.AVAILABLE_IDS, StateResource.VETOES)));
        values.put(ScenePathEnforcer.RERANK,
                access(set(StateResource.RAW_PRODUCTS, StateResource.PROFILE),
                        set(StateResource.RANKED_PRODUCTS)));
        values.put(ScenePathEnforcer.GENERATE_LOCALIZED_COPY,
                access(set(StateResource.FINAL_PRODUCTS), set(StateResource.COPIES)));
        values.put(ScenePathEnforcer.GENERATE_RETENTION_COPY,
                access(set(StateResource.FINAL_PRODUCTS), set(StateResource.COPIES)));
        return Map.copyOf(values);
    }

    private static StateAccess access(Set<StateResource> reads, Set<StateResource> writes) {
        return new StateAccess(reads, writes);
    }

    private static Set<StateResource> set(StateResource... values) {
        return values.length == 0 ? Set.of() : EnumSet.copyOf(List.of(values));
    }

    public record PlannedSpecialistAction(AgentId agent, String action) {}

    private record StateAccess(Set<StateResource> reads, Set<StateResource> writes) {}

    private enum StateResource {
        PROFILE,
        ORDER_CONTEXT,
        CAMPAIGN_CONSTRAINTS,
        RAW_PRODUCTS,
        FULFILLMENT,
        AVAILABLE_IDS,
        VETOES,
        RANKED_PRODUCTS,
        FINAL_PRODUCTS,
        COPIES
    }
}
