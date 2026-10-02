package com.ecommerce.service;

import com.ecommerce.model.AgentId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side contract for the bounded recommendation planner. The planner
 * may choose the next action from the current goal/state, but it can only
 * advance along this scene's required capability path.
 */
@Component
public class ScenePathEnforcer {

    public static final String SCENE_HOMEPAGE = "homepage";
    public static final String SCENE_CAMPAIGN = "campaign";
    public static final String SCENE_RETENTION = "retention";

    public static final String GET_USER_PROFILE = "get_user_profile";
    public static final String LOAD_CAMPAIGN_CONSTRAINTS = "load_campaign_constraints";
    public static final String GET_RECENT_ORDERS = "get_recent_orders";
    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String CHECK_FULFILLMENT = "check_fulfillment";
    public static final String CHECK_INVENTORY = "check_inventory";
    public static final String RERANK = "rerank";
    public static final String GENERATE_LOCALIZED_COPY = "generate_localized_copy";
    public static final String GENERATE_RETENTION_COPY = "generate_retention_copy";
    public static final String FINAL_ACTION = "final_answer";

    private static final Map<String, List<String>> SCENE_PATHS = new LinkedHashMap<>();
    private static final Map<String, SceneContract> CONTRACTS = new LinkedHashMap<>();

    static {
        SCENE_PATHS.put(SCENE_HOMEPAGE, List.of(
                GET_USER_PROFILE, SEARCH_PRODUCTS, CHECK_INVENTORY, RERANK, FINAL_ACTION));
        SCENE_PATHS.put(SCENE_CAMPAIGN, List.of(
                LOAD_CAMPAIGN_CONSTRAINTS, SEARCH_PRODUCTS, CHECK_FULFILLMENT,
                CHECK_INVENTORY, RERANK, GENERATE_LOCALIZED_COPY, FINAL_ACTION));
        SCENE_PATHS.put(SCENE_RETENTION, List.of(
                GET_USER_PROFILE, GET_RECENT_ORDERS, SEARCH_PRODUCTS, CHECK_INVENTORY,
                RERANK, GENERATE_RETENTION_COPY, FINAL_ACTION));

        CONTRACTS.put(SCENE_HOMEPAGE, new SceneContract(
                List.of("user_profile", "product_recall", "inventory", "rerank"),
                List.of("campaign_constraints", "recent_orders", "localized_copy", "retention_copy"),
                Map.of(
                        "user_profile", "profile evidence exists",
                        "product_recall", "market-filtered candidate products exist",
                        "inventory", "country inventory evidence exists",
                        "rerank", "ranked products and final eligible products exist")));
        CONTRACTS.put(SCENE_CAMPAIGN, new SceneContract(
                List.of("campaign_constraints", "product_recall", "fulfillment", "inventory", "rerank", "localized_copy"),
                List.of("user_profile", "recent_orders", "retention_copy"),
                Map.of(
                        "campaign_constraints", "campaign constraints are loaded",
                        "product_recall", "market-filtered candidate products exist",
                        "fulfillment", "campaign delivery/fulfillment candidates are checked",
                        "inventory", "country inventory evidence exists",
                        "rerank", "ranked products and final eligible products exist",
                        "localized_copy", "localized copy exists for the final products")));
        CONTRACTS.put(SCENE_RETENTION, new SceneContract(
                List.of("user_profile", "recent_orders", "product_recall", "inventory", "rerank", "retention_copy"),
                List.of("campaign_constraints", "localized_copy"),
                Map.of(
                        "user_profile", "profile evidence exists",
                        "recent_orders", "recent order context exists",
                        "product_recall", "market-filtered candidate products exist",
                        "inventory", "country inventory evidence exists",
                        "rerank", "ranked products and final eligible products exist",
                        "retention_copy", "retention copy exists for the final products")));
    }

    /** Canonical whitelist. Legacy aliases are normalized before execution. */
    public static final List<String> DEFAULT_WHITELIST = List.of(
            GET_USER_PROFILE, LOAD_CAMPAIGN_CONSTRAINTS, GET_RECENT_ORDERS,
            SEARCH_PRODUCTS, CHECK_FULFILLMENT, CHECK_INVENTORY, RERANK,
            RecommendationPipelineExecutor.FILTER_PRODUCTS,
            GENERATE_LOCALIZED_COPY, GENERATE_RETENTION_COPY, FINAL_ACTION);

    public String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) return SCENE_HOMEPAGE;
        String value = scene.trim().toLowerCase();
        return SCENE_PATHS.containsKey(value) ? value : SCENE_HOMEPAGE;
    }

    public List<String> pathFor(String scene) {
        return SCENE_PATHS.get(normalizeScene(scene));
    }

    /** Recommended tool order for a scene. Not a hard next-tool lock for specialists. */
    public List<String> recommendedPath(String scene) {
        return pathFor(scene);
    }

    public List<String> toolsFor(AgentId agent) {
        if (agent == null) {
            return List.of();
        }
        return switch (agent) {
            case PROFILE -> List.of(GET_USER_PROFILE, GET_RECENT_ORDERS);
            case PRODUCT -> List.of(SEARCH_PRODUCTS, RERANK);
            case INVENTORY -> List.of(LOAD_CAMPAIGN_CONSTRAINTS, CHECK_FULFILLMENT, CHECK_INVENTORY);
            case COPY -> List.of(GENERATE_LOCALIZED_COPY, GENERATE_RETENTION_COPY);
            case SUPERVISOR -> List.of(FINAL_ACTION);
        };
    }

    /**
     * RULES fallback for supervisor routing. Owner of {@link #expectedNextStep} unless
     * an unhandled veto still has a re-recall slot.
     */
    public AgentId expectedNextAgent(String scene, RecommendationPipelineState context) {
        if (context != null && context.hasUnhandledVeto() && context.getRecallAfterVetoCount() < 1) {
            return AgentId.PRODUCT;
        }
        return agentForTool(expectedNextStep(scene, context));
    }

    public AgentId agentForTool(String tool) {
        String canonical = canonicalTool(tool);
        if (FINAL_ACTION.equals(canonical)) {
            return AgentId.SUPERVISOR;
        }
        for (AgentId agent : List.of(AgentId.PROFILE, AgentId.PRODUCT, AgentId.INVENTORY, AgentId.COPY)) {
            if (toolsFor(agent).contains(canonical)) {
                return agent;
            }
        }
        return AgentId.SUPERVISOR;
    }

    public boolean isToolAllowedFor(AgentId agent, String tool) {
        return toolsFor(agent).contains(canonicalTool(tool));
    }

    /** Specialists that can make progress from the current blackboard. */
    public List<AgentId> allowedAgents(String scene, RecommendationPipelineState context) {
        if (isPathComplete(scene, context)) return List.of(AgentId.SUPERVISOR);
        List<AgentId> allowed = new ArrayList<>();
        for (AgentId agent : List.of(AgentId.PROFILE, AgentId.PRODUCT, AgentId.INVENTORY, AgentId.COPY)) {
            if (!executableTools(agent, scene, context).isEmpty()) allowed.add(agent);
        }
        return allowed;
    }

    /** Tools a specialist may safely choose now, after prerequisites are checked. */
    public List<String> executableTools(AgentId agent, String scene, RecommendationPipelineState context) {
        if (agent == null || context == null) return List.of();
        String normalized = normalizeScene(scene);
        List<String> executable = new ArrayList<>();
        if (agent == AgentId.PROFILE) {
            if (!SCENE_CAMPAIGN.equals(normalized)
                    && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT)) {
                executable.add(GET_USER_PROFILE);
            }
            if (SCENE_RETENTION.equals(normalized)
                    && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.ORDER_CONTEXT_RESULT)) {
                executable.add(GET_RECENT_ORDERS);
            }
        } else if (agent == AgentId.PRODUCT) {
            if (context.hasUnhandledVeto() && context.getRecallAfterVetoCount() < 1) {
                executable.add(SEARCH_PRODUCTS);
            } else if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT)) {
                boolean profileReady = SCENE_CAMPAIGN.equals(normalized) || context.getProfile() != null;
                boolean ordersReady = !SCENE_RETENTION.equals(normalized) || context.getOrderContext() != null;
                // Campaign constraints are consumed by fulfillment, not product recall, so both
                // independent actions may be scheduled in the same parallel batch.
                if (profileReady && ordersReady) executable.add(SEARCH_PRODUCTS);
            }
            // Ranking reads candidates/profile only. Final filtering is performed by the
            // Supervisor after the parallel ranking + inventory barrier has completed.
            if (context.getRawProducts() != null && context.getRankedProducts() == null
                    && !context.hasUnhandledVeto()) executable.add(RERANK);
        } else if (agent == AgentId.INVENTORY) {
            if (SCENE_CAMPAIGN.equals(normalized) && context.getCampaignConstraints() == null) {
                executable.add(LOAD_CAMPAIGN_CONSTRAINTS);
            }
            if (context.getRawProducts() != null) {
                if (SCENE_CAMPAIGN.equals(normalized)
                        && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_RESULT)) {
                    executable.add(CHECK_FULFILLMENT);
                }
                boolean fulfillmentReady = !SCENE_CAMPAIGN.equals(normalized)
                        || context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_RESULT);
                if (fulfillmentReady
                        && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT)) {
                    executable.add(CHECK_INVENTORY);
                }
            }
        } else if (agent == AgentId.COPY && context.getFinalProducts() != null) {
            if (SCENE_CAMPAIGN.equals(normalized)
                    && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT)) {
                executable.add(GENERATE_LOCALIZED_COPY);
            }
            if (SCENE_RETENTION.equals(normalized)
                    && !context.getAgentResults().containsKey(RecommendationPipelineExecutor.RETENTION_MARKETING_COPY_RESULT)) {
                executable.add(GENERATE_RETENTION_COPY);
            }
        }
        return executable;
    }

    public SceneContract contractFor(String scene) {
        return CONTRACTS.get(normalizeScene(scene));
    }

    public List<String> requiredCapabilities(String scene) {
        return contractFor(scene).requiredCapabilities();
    }

    public List<String> optionalCapabilities(String scene) {
        return contractFor(scene).optionalCapabilities();
    }

    public Map<String, String> completionConditions(String scene) {
        return contractFor(scene).completionConditions();
    }

    public Set<String> sceneTools() {
        return SCENE_PATHS.values().stream()
                .flatMap(List::stream)
                .filter(tool -> !FINAL_ACTION.equals(tool))
                .collect(Collectors.toSet());
    }

    /** Normalize old public tool names without weakening the server contract. */
    public String canonicalTool(String tool) {
        if (tool == null) return "";
        return switch (tool.trim()) {
            case "get_campaign_constraints" -> LOAD_CAMPAIGN_CONSTRAINTS;
            case "get_order_context" -> GET_RECENT_ORDERS;
            case "search_cross_border_products" -> SEARCH_PRODUCTS;
            case "check_market_eligibility" -> CHECK_FULFILLMENT;
            case "check_fulfillment_inventory" -> CHECK_INVENTORY;
            case "rerank_products" -> RERANK;
            case "filter_products" -> "filter_products";
            default -> tool.trim();
        };
    }

    public List<String> normalizeWhitelist(List<String> whitelist) {
        List<String> input = whitelist == null || whitelist.isEmpty() ? DEFAULT_WHITELIST : whitelist;
        return input.stream()
                .map(this::canonicalTool)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    /** State-driven next action: required capabilities are checked one by one. */
    public String expectedNextStep(String scene, RecommendationPipelineState context) {
        if (context != null && context.hasUnhandledVeto() && context.getRecallAfterVetoCount() < 1) {
            return SEARCH_PRODUCTS;
        }
        List<String> path = pathFor(scene);
        Map<String, Boolean> completed = completionMap(scene, context);
        for (String tool : path) {
            if (!FINAL_ACTION.equals(tool) && !Boolean.TRUE.equals(completed.get(tool))) return tool;
        }
        return FINAL_ACTION;
    }

    public boolean isExpectedStep(String scene, String proposedAction, RecommendationPipelineState context) {
        String action = canonicalTool(proposedAction);
        return action.equals(expectedNextStep(scene, context));
    }

    public boolean isPathComplete(String scene, RecommendationPipelineState context) {
        return FINAL_ACTION.equals(expectedNextStep(scene, context));
    }

    private Map<String, Boolean> completionMap(String scene, RecommendationPipelineState context) {
        Map<String, Boolean> completed = new LinkedHashMap<>();
        pathFor(scene).forEach(tool -> completed.put(tool, false));
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT)) {
            completed.put(GET_USER_PROFILE, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.CAMPAIGN_CONSTRAINTS_RESULT)) {
            completed.put(LOAD_CAMPAIGN_CONSTRAINTS, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.ORDER_CONTEXT_RESULT)) {
            completed.put(GET_RECENT_ORDERS, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT)) {
            completed.put(SEARCH_PRODUCTS, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_RESULT)) {
            completed.put(CHECK_FULFILLMENT, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT)) {
            completed.put(CHECK_INVENTORY, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.RERANK_RESULT)
                || context.getRankedProducts() != null) {
            completed.put(RERANK, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT)) {
            completed.put(GENERATE_LOCALIZED_COPY, true);
        }
        if (context.getAgentResults().containsKey(RecommendationPipelineExecutor.RETENTION_MARKETING_COPY_RESULT)) {
            completed.put(GENERATE_RETENTION_COPY, true);
        }
        return completed;
    }

    public String describeScene(String scene) {
        return switch (normalizeScene(scene)) {
            case SCENE_CAMPAIGN -> "campaign (promotion-constrained)";
            case SCENE_RETENTION -> "retention (win-back)";
            default -> "homepage (default)";
        };
    }

    public record SceneContract(
            List<String> requiredCapabilities,
            List<String> optionalCapabilities,
            Map<String, String> completionConditions) {
        public SceneContract {
            requiredCapabilities = List.copyOf(new ArrayList<>(requiredCapabilities));
            optionalCapabilities = List.copyOf(new ArrayList<>(optionalCapabilities));
            completionConditions = Map.copyOf(new LinkedHashMap<>(completionConditions));
        }
    }
}
