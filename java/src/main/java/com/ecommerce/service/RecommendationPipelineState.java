package com.ecommerce.service;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;

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
}
