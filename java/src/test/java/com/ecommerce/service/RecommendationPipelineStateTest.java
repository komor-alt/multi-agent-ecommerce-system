package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.BlackboardWriteResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.VetoRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationPipelineStateTest {

    @Test
    void leaseLossClosesAndUnblocksAnAwaitingTool() throws Exception {
        RecommendationPipelineState state = newState();
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        state.setExecutionGuard(() -> {
            entered.countDown();
            if (lost.get()) throw new com.ecommerce.runtime.persistence.StaleExecutionLeaseException(state.getRunId());
        });
        var tool = new java.util.concurrent.CompletableFuture<String>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var waiter = executor.submit(() -> state.await(tool));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            lost.set(true);
            state.close();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> waiter.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.ecommerce.runtime.persistence.StaleExecutionLeaseException.class);
            assertTrue(tool.isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closedContextCancelsFutureEvenIfClosePrecededAwaitRegistration() {
        RecommendationPipelineState state = newState();
        state.close();
        var tool = new java.util.concurrent.CompletableFuture<String>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> state.await(tool))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertTrue(tool.isCancelled());
    }

    @Test
    void copyCannotWriteProductFields() {
        RecommendationPipelineState state = newState();
        Product extra = Product.builder().productId("p-extra").name("smuggled").build();

        BlackboardWriteResult raw = state.write(AgentId.COPY, BlackboardField.RAW_PRODUCTS, List.of(extra));
        BlackboardWriteResult ranked = state.write(AgentId.COPY, BlackboardField.RANKED_PRODUCTS, List.of(extra));
        BlackboardWriteResult finals = state.write(AgentId.COPY, BlackboardField.FINAL_PRODUCTS, List.of(extra));

        assertFalse(raw.accepted());
        assertFalse(ranked.accepted());
        assertFalse(finals.accepted());
        assertNull(state.getRawProducts());
        assertNull(state.getRankedProducts());
        assertNull(state.getFinalProducts());
        assertEquals(3, state.getDeniedWrites().size());
    }

    @Test
    void recallCanWriteCandidatesAndConstraintCanVeto() {
        RecommendationPipelineState state = newState();
        Product phone = Product.builder().productId("p-1").name("phone").build();

        assertTrue(state.write(AgentId.PRODUCT, BlackboardField.RAW_PRODUCTS, List.of(phone)).accepted());
        assertEquals(1, state.getRawProducts().size());

        VetoRecord veto = VetoRecord.builder()
                .source(AgentId.INVENTORY)
                .productIds(List.of("p-1"))
                .reason("out of stock")
                .build();
        assertTrue(state.write(AgentId.INVENTORY, BlackboardField.VETOES, veto).accepted());
        assertTrue(state.hasUnhandledVeto());
        assertTrue(state.vetoedProductIds().contains("p-1"));
        assertFalse(state.write(AgentId.COPY, BlackboardField.VETOES, veto).accepted());
    }

    @Test
    void rerecallInvalidatesAllResultsDerivedFromOldCandidates() {
        RecommendationPipelineState state = newState();
        Product oldCandidate = Product.builder().productId("p-old").name("old candidate").build();
        AgentResult staleResult = AgentResult.builder().agentName("stale").data(Map.of()).build();

        state.setRawProducts(List.of(oldCandidate));
        state.setMarketEligibleIds(Set.of("p-old"));
        state.setFulfillmentEligibleIds(Set.of("p-old"));
        state.setFulfillment(Map.of("p-old", "eligible"));
        state.setAvailableIds(Set.of("p-old"));
        state.setRankedProducts(List.of(oldCandidate));
        state.setFinalProducts(List.of(oldCandidate));
        state.setCopies(List.of(Map.of("productId", "p-old", "copy", "stale")));
        state.putAgentResult(RecommendationPipelineExecutor.FULFILLMENT_RESULT, staleResult);
        state.putAgentResult(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT, staleResult);
        state.putAgentResult(RecommendationPipelineExecutor.RERANK_RESULT, staleResult);
        state.putAgentResult(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT, staleResult);
        state.addEvidenceIds(List.of(
                "profile:u1", "product:p-old", "fulfillment:p-old", "inventory:p-old", "copy:p-old"));

        state.clearAfterRerecall();

        assertNull(state.getMarketEligibleIds());
        assertNull(state.getFulfillmentEligibleIds());
        assertEquals(Map.of(), state.getFulfillment());
        assertNull(state.getAvailableIds());
        assertNull(state.getRankedProducts());
        assertNull(state.getFinalProducts());
        assertNull(state.getCopies());
        assertTrue(state.getAgentResults().isEmpty());
        assertEquals(Set.of("profile:u1"), state.getKnownEvidenceIds());
        assertEquals(List.of(oldCandidate), state.getRawProducts());
    }

    @Test
    void staleParallelResultsCannotOverwriteANewerCandidateVersion() {
        RecommendationPipelineState state = newState();
        Product first = Product.builder().productId("p-1").build();
        Product replacement = Product.builder().productId("p-2").build();
        state.setRawProducts(List.of(first));
        long staleVersion = state.getCandidateVersion();

        state.setRawProducts(List.of(replacement));

        assertFalse(state.setRankedProductsIfCurrent(staleVersion, List.of(first)));
        assertFalse(state.applyInventoryIfCurrent(staleVersion, Set.of("p-1"), Map.of()));
        assertFalse(state.applyCandidatePatch(new CandidateStatePatch(
                state.getCandidateVersion(), AgentId.COPY, List.of(first), null, Map.of())));
        assertNull(state.getRankedProducts());
        assertNull(state.getAvailableIds());

        long currentVersion = state.getCandidateVersion();
        assertTrue(state.setRankedProductsIfCurrent(currentVersion, List.of(replacement)));
        assertTrue(state.applyInventoryIfCurrent(currentVersion, Set.of("p-2"), Map.of()));
        assertTrue(state.candidateDerivedResultsCurrent());
        assertTrue(state.writeFinalProductsIfCurrent(AgentId.SUPERVISOR, List.of(replacement)));
        assertEquals(List.of(replacement), state.getFinalProducts());
    }

    private static RecommendationPipelineState newState() {
        return new RecommendationPipelineState("run-1", RecommendationRequest.builder()
                .userId("u1")
                .scene("homepage")
                .build());
    }
}
