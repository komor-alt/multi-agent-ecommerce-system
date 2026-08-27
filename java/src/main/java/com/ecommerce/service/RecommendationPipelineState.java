package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentMessage;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.BlackboardWriteResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.model.VetoRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecommendationPipelineState {
    private final String runId;
    private final RecommendationRequest request;
    private final Map<String, AgentResult> agentResults = new LinkedHashMap<>();
    private final Set<String> knownEvidenceIds = new HashSet<>();
    private UserProfile profile;
    private List<Product> rawProducts;
    private List<Product> rankedProducts;
    private Set<String> availableIds;
    private List<Product> finalProducts;
    private List<Map<String, String>> copies;
    private Map<String, Object> campaignConstraints;
    private List<Map<String, Object>> orderContext;
    private Set<String> marketEligibleIds;
    private Set<String> fulfillmentEligibleIds;
    private Map<String, Object> fulfillment = new LinkedHashMap<>();
    private Map<String, Object> dataSources = new LinkedHashMap<>();
    private LlmCallBudget llmBudget;
    private final List<AgentMessage> messages = new ArrayList<>();
    private final List<VetoRecord> vetoes = new ArrayList<>();
    private final List<BlackboardWriteResult> deniedWrites = new ArrayList<>();
    private final List<Map<String, String>> invalidAgentSelections = new ArrayList<>();
    private int recallAfterVetoCount;

    public RecommendationPipelineState(String runId, RecommendationRequest request) {
        this.runId = runId;
        this.request = request;
    }

    public String getRunId() {
        return runId;
    }

    public RecommendationRequest getRequest() {
        return request;
    }

    public Map<String, AgentResult> getAgentResults() {
        return agentResults;
    }

    public void putAgentResult(String key, AgentResult result) {
        agentResults.put(key, result);
    }

    public Set<String> getKnownEvidenceIds() {
        return knownEvidenceIds;
    }

    public void addEvidenceIds(List<String> evidenceIds) {
        knownEvidenceIds.addAll(evidenceIds);
    }

    public UserProfile getProfile() {
        return profile;
    }

    public void setProfile(UserProfile profile) {
        this.profile = profile;
    }

    public List<Product> getRawProducts() {
        return rawProducts;
    }

    public void setRawProducts(List<Product> rawProducts) {
        this.rawProducts = rawProducts;
    }

    public List<Product> getRankedProducts() {
        return rankedProducts;
    }

    public void setRankedProducts(List<Product> rankedProducts) {
        this.rankedProducts = rankedProducts;
    }

    public Set<String> getAvailableIds() {
        return availableIds;
    }

    public void setAvailableIds(Set<String> availableIds) {
        this.availableIds = availableIds;
    }

    public List<Product> getFinalProducts() {
        return finalProducts;
    }

    public void setFinalProducts(List<Product> finalProducts) {
        this.finalProducts = finalProducts;
    }

    public List<Map<String, String>> getCopies() {
        return copies;
    }

    public void setCopies(List<Map<String, String>> copies) {
        this.copies = copies;
    }

    public Map<String, Object> getCampaignConstraints() { return campaignConstraints; }
    public void setCampaignConstraints(Map<String, Object> value) { this.campaignConstraints = value; }
    public List<Map<String, Object>> getOrderContext() { return orderContext; }
    public void setOrderContext(List<Map<String, Object>> value) { this.orderContext = value; }
    public Set<String> getMarketEligibleIds() { return marketEligibleIds; }
    public void setMarketEligibleIds(Set<String> value) { this.marketEligibleIds = value; }
    public Set<String> getFulfillmentEligibleIds() { return fulfillmentEligibleIds; }
    public void setFulfillmentEligibleIds(Set<String> value) { this.fulfillmentEligibleIds = value; }
    public Map<String, Object> getFulfillment() { return fulfillment; }
    public void setFulfillment(Map<String, Object> value) {
        this.fulfillment = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public Map<String, Object> getDataSources() { return dataSources; }
    public void putDataSource(String name, Object source) {
        if (name != null && source != null) {
            dataSources.put(name, source);
        }
    }

    public LlmCallBudget getLlmBudget() {
        return llmBudget;
    }

    public void setLlmBudget(LlmCallBudget llmBudget) {
        this.llmBudget = llmBudget;
    }

    public boolean readyForFinalAnswer() {
        return rawProducts != null && rankedProducts != null && availableIds != null && finalProducts != null;
    }

    public List<String> evidenceIds() {
        return new ArrayList<>(knownEvidenceIds);
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public void addMessage(AgentMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    public List<VetoRecord> getVetoes() {
        return vetoes;
    }

    public List<BlackboardWriteResult> getDeniedWrites() {
        return deniedWrites;
    }

    public List<Map<String, String>> getInvalidAgentSelections() {
        return invalidAgentSelections;
    }

    public void recordInvalidAgentSelection(AgentId proposed, AgentId fallback, String reason) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("proposed", proposed == null ? "" : proposed.name());
        row.put("fallback", fallback == null ? "" : fallback.name());
        row.put("reason", reason == null ? "" : reason);
        invalidAgentSelections.add(row);
    }

    public int getRecallAfterVetoCount() {
        return recallAfterVetoCount;
    }

    public void incrementRecallAfterVetoCount() {
        recallAfterVetoCount++;
    }

    public boolean hasUnhandledVeto() {
        return vetoes.stream().anyMatch(veto -> !veto.isHandled());
    }

    public void markVetoesHandled() {
        vetoes.forEach(veto -> veto.setHandled(true));
    }

    public void clearAfterRerecall() {
        agentResults.remove(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT);
        agentResults.remove(RecommendationPipelineExecutor.RERANK_RESULT);
        availableIds = null;
        rankedProducts = null;
        finalProducts = null;
    }

    public Set<String> vetoedProductIds() {
        Set<String> ids = new HashSet<>();
        for (VetoRecord veto : vetoes) {
            if (veto.getProductIds() != null) {
                ids.addAll(veto.getProductIds());
            }
        }
        return ids;
    }

    public static AgentId ownerOf(BlackboardField field) {
        return switch (field) {
            case PROFILE, RAW_PRODUCTS, RANKED_PRODUCTS -> AgentId.RECALL;
            case AVAILABLE_IDS, VETOES -> AgentId.CONSTRAINT;
            case FINAL_PRODUCTS -> AgentId.SUPERVISOR;
            case COPIES -> AgentId.COPY;
        };
    }

    /**
     * Agent-facing blackboard write. Trusted pipeline code may still use setters.
     * Copy cannot write any product list field.
     */
    @SuppressWarnings("unchecked")
    public BlackboardWriteResult write(AgentId agent, BlackboardField field, Object value) {
        if (agent == null || field == null) {
            return deny("agent and field are required");
        }
        AgentId owner = ownerOf(field);
        if (agent != owner) {
            return deny(agent + " cannot write " + field + "; owner is " + owner);
        }
        switch (field) {
            case PROFILE -> setProfile((UserProfile) value);
            case RAW_PRODUCTS -> setRawProducts((List<Product>) value);
            case RANKED_PRODUCTS -> setRankedProducts((List<Product>) value);
            case AVAILABLE_IDS -> setAvailableIds((Set<String>) value);
            case FINAL_PRODUCTS -> setFinalProducts((List<Product>) value);
            case COPIES -> setCopies((List<Map<String, String>>) value);
            case VETOES -> {
                if (value instanceof VetoRecord vetoRecord) {
                    vetoes.add(vetoRecord);
                } else if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof VetoRecord vetoRecord) {
                            vetoes.add(vetoRecord);
                        }
                    }
                } else {
                    return deny("VETOES requires a VetoRecord or List<VetoRecord>");
                }
            }
        }
        return BlackboardWriteResult.ok();
    }

    private BlackboardWriteResult deny(String reason) {
        BlackboardWriteResult denied = BlackboardWriteResult.denied(reason);
        deniedWrites.add(denied);
        return denied;
    }
}
