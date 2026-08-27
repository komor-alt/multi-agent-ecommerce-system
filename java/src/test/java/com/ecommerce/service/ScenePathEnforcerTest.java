package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.VetoRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePathEnforcerTest {

    private final ScenePathEnforcer enforcer = new ScenePathEnforcer();

    @Test
    void recommendedPathKeepsSceneCompletionContract() {
        assertEquals(enforcer.pathFor("homepage"), enforcer.recommendedPath("homepage"));
        assertTrue(enforcer.requiredCapabilities("homepage").contains("inventory"));
        assertTrue(enforcer.completionConditions("campaign").containsKey("localized_copy"));
    }

    @Test
    void expectedNextAgentIsOwnerOfExpectedNextTool() {
        RecommendationPipelineState empty = newState("homepage");
        assertEquals(AgentId.RECALL, enforcer.expectedNextAgent("homepage", empty));
        assertEquals(AgentId.RECALL, enforcer.agentForTool(ScenePathEnforcer.GET_USER_PROFILE));
        assertEquals(AgentId.CONSTRAINT, enforcer.agentForTool(ScenePathEnforcer.CHECK_INVENTORY));
        assertEquals(AgentId.COPY, enforcer.agentForTool(ScenePathEnforcer.GENERATE_LOCALIZED_COPY));
        assertEquals(AgentId.SUPERVISOR, enforcer.agentForTool(ScenePathEnforcer.FINAL_ACTION));

        RecommendationPipelineState afterSearch = newState("homepage");
        afterSearch.putAgentResult(RecommendationPipelineExecutor.USER_PROFILE_RESULT, ok());
        afterSearch.putAgentResult(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, ok());
        assertEquals(AgentId.CONSTRAINT, enforcer.expectedNextAgent("homepage", afterSearch));

        RecommendationPipelineState ready = newState("homepage");
        ready.putAgentResult(RecommendationPipelineExecutor.USER_PROFILE_RESULT, ok());
        ready.putAgentResult(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, ok());
        ready.putAgentResult(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT, ok());
        ready.putAgentResult(RecommendationPipelineExecutor.RERANK_RESULT, ok());
        ready.setRankedProducts(List.of(Product.builder().productId("p-1").build()));
        assertEquals(AgentId.SUPERVISOR, enforcer.expectedNextAgent("homepage", ready));
    }

    @Test
    void unhandledVetoRoutesBackToRecallOnce() {
        RecommendationPipelineState state = newState("campaign");
        markCampaignReadyForCopy(state);
        state.getVetoes().add(VetoRecord.builder()
                .source(AgentId.CONSTRAINT)
                .productIds(List.of("p-low"))
                .reason("low stock")
                .build());

        assertEquals(AgentId.RECALL, enforcer.expectedNextAgent("campaign", state));
        assertEquals(ScenePathEnforcer.SEARCH_PRODUCTS, enforcer.expectedNextStep("campaign", state));
        state.incrementRecallAfterVetoCount();
        state.getVetoes().get(0).setHandled(true);
        assertEquals(AgentId.COPY, enforcer.expectedNextAgent("campaign", state));
    }

    @Test
    void specialistToolAllowlistsDoNotOverlapProductMutationAcrossCopy() {
        assertTrue(enforcer.isToolAllowedFor(AgentId.RECALL, "search_products"));
        assertTrue(enforcer.isToolAllowedFor(AgentId.CONSTRAINT, "check_inventory"));
        assertTrue(enforcer.isToolAllowedFor(AgentId.COPY, "generate_localized_copy"));
        assertFalse(enforcer.isToolAllowedFor(AgentId.COPY, "search_products"));
        assertFalse(enforcer.isToolAllowedFor(AgentId.COPY, "check_inventory"));
    }

    private static void markCampaignReadyForCopy(RecommendationPipelineState state) {
        state.putAgentResult(RecommendationPipelineExecutor.CAMPAIGN_CONSTRAINTS_RESULT, ok());
        state.putAgentResult(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, ok());
        state.putAgentResult(RecommendationPipelineExecutor.FULFILLMENT_RESULT, ok());
        state.putAgentResult(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT, ok());
        state.putAgentResult(RecommendationPipelineExecutor.RERANK_RESULT, ok());
        state.setRawProducts(List.of(Product.builder().productId("p-low").build()));
        state.setAvailableIds(Set.of());
        state.setRankedProducts(List.of());
    }

    private static AgentResult ok() {
        return AgentResult.builder().success(true).build();
    }

    private static RecommendationPipelineState newState(String scene) {
        return new RecommendationPipelineState("run-1", RecommendationRequest.builder()
                .userId("u1")
                .scene(scene)
                .build());
    }
}
