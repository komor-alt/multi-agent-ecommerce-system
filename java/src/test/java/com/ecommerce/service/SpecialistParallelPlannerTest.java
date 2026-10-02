package com.ecommerce.service;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialistParallelPlannerTest {
    private final ScenePathEnforcer pathEnforcer = new ScenePathEnforcer();

    @Test
    void campaignStartsConstraintsAndRecallInOneBatch() {
        RecommendationPipelineState state = state("campaign");

        List<SpecialistParallelPlanner.PlannedSpecialistAction> batch = planner().plan(
                state, ScenePathEnforcer.DEFAULT_WHITELIST, 8);

        assertThat(batch).containsExactly(
                new SpecialistParallelPlanner.PlannedSpecialistAction(
                        AgentId.INVENTORY, ScenePathEnforcer.LOAD_CAMPAIGN_CONSTRAINTS),
                new SpecialistParallelPlanner.PlannedSpecialistAction(
                        AgentId.PRODUCT, ScenePathEnforcer.SEARCH_PRODUCTS));
    }

    @Test
    void homepageRunsInventoryAndRerankInOneBatchAfterRecall() {
        RecommendationPipelineState state = state("homepage");
        Product product = Product.builder().productId("p-1").build();
        state.setProfile(UserProfile.builder().userId("u-1").build());
        state.putAgentResult(RecommendationPipelineExecutor.USER_PROFILE_RESULT, success());
        state.setRawProducts(List.of(product));
        state.putAgentResult(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, success());

        List<SpecialistParallelPlanner.PlannedSpecialistAction> batch = planner().plan(
                state, ScenePathEnforcer.DEFAULT_WHITELIST, 6);

        assertThat(batch).containsExactly(
                new SpecialistParallelPlanner.PlannedSpecialistAction(
                        AgentId.INVENTORY, ScenePathEnforcer.CHECK_INVENTORY),
                new SpecialistParallelPlanner.PlannedSpecialistAction(
                        AgentId.PRODUCT, ScenePathEnforcer.RERANK));
    }

    @Test
    void fulfillmentWriteConflictPreventsUnsafeBatch() {
        RecommendationPipelineState state = state("campaign");
        state.setCampaignConstraints(Map.of("maxDeliveryDays", 7));
        state.putAgentResult(RecommendationPipelineExecutor.CAMPAIGN_CONSTRAINTS_RESULT, success());
        state.setRawProducts(List.of(Product.builder().productId("p-1").build()));
        state.putAgentResult(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, success());

        assertThat(planner().plan(state, ScenePathEnforcer.DEFAULT_WHITELIST, 6)).isEmpty();
    }

    @Test
    void parallelPlanningCanBeDisabled() {
        RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
        properties.setParallelEnabled(false);
        SpecialistParallelPlanner planner = new SpecialistParallelPlanner(pathEnforcer, properties);

        assertThat(planner.plan(state("campaign"), ScenePathEnforcer.DEFAULT_WHITELIST, 8)).isEmpty();
    }

    private SpecialistParallelPlanner planner() {
        return new SpecialistParallelPlanner(pathEnforcer, new RecommendationOrchestrationProperties());
    }

    private RecommendationPipelineState state(String scene) {
        return new RecommendationPipelineState("run-1", RecommendationRequest.builder()
                .userId("u-1").scene(scene).numItems(2).build());
    }

    private AgentResult success() {
        return AgentResult.builder().agentName("test").success(true).data(Map.of()).build();
    }
}
