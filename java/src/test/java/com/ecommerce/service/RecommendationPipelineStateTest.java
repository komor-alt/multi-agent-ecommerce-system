package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.BlackboardWriteResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.VetoRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationPipelineStateTest {

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

        assertTrue(state.write(AgentId.RECALL, BlackboardField.RAW_PRODUCTS, List.of(phone)).accepted());
        assertEquals(1, state.getRawProducts().size());

        VetoRecord veto = VetoRecord.builder()
                .source(AgentId.CONSTRAINT)
                .productIds(List.of("p-1"))
                .reason("out of stock")
                .build();
        assertTrue(state.write(AgentId.CONSTRAINT, BlackboardField.VETOES, veto).accepted());
        assertTrue(state.hasUnhandledVeto());
        assertTrue(state.vetoedProductIds().contains("p-1"));
        assertFalse(state.write(AgentId.COPY, BlackboardField.VETOES, veto).accepted());
    }

    private static RecommendationPipelineState newState() {
        return new RecommendationPipelineState("run-1", RecommendationRequest.builder()
                .userId("u1")
                .scene("homepage")
                .build());
    }
}
