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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

public class RecommendationPipelineState {
    private final String runId;
    private final RecommendationRequest request;
    private final Map<String, AgentResult> agentResults = new ConcurrentHashMap<>();
    private final Set<String> knownEvidenceIds = ConcurrentHashMap.newKeySet();
    private volatile UserProfile profile;
    private volatile List<Product> rawProducts;
    private volatile List<Product> rankedProducts;
    private volatile Set<String> availableIds;
    private final AtomicLong candidateVersion = new AtomicLong();
    private volatile long rankedCandidateVersion = -1;
    private volatile long inventoryCandidateVersion = -1;
    private volatile List<Product> finalProducts;
    private volatile List<Map<String, String>> copies;
    private volatile Map<String, Object> campaignConstraints;
    private volatile List<Map<String, Object>> orderContext;
    private volatile Set<String> marketEligibleIds;
    private volatile Set<String> fulfillmentEligibleIds;
    private volatile Map<String, Object> fulfillment = Map.of();
    private final Map<String, Object> dataSources = new ConcurrentHashMap<>();
    private LlmCallBudget llmBudget;
    private final List<AgentMessage> messages = new ArrayList<>();
    private final List<VetoRecord> vetoes = new CopyOnWriteArrayList<>();
    private final List<BlackboardWriteResult> deniedWrites = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> invalidAgentSelections = new ArrayList<>();
    private int recallAfterVetoCount;
    private volatile long deadlineNanos = Long.MAX_VALUE;
    private volatile boolean closed;
    private volatile Runnable executionGuard = () -> {};
    private final Set<CompletableFuture<?>> pendingFutures = ConcurrentHashMap.newKeySet();

    public RecommendationPipelineState(String runId, RecommendationRequest request) {
        this.runId = runId;
        this.request = request;
    }

    public String getRunId() {
        return runId;
    }

    public void setDeadline(Duration timeout) {
        deadlineNanos = System.nanoTime() + timeout.toNanos();
    }

    public void checkActive() {
        executionGuard.run();
        if (closed || Thread.currentThread().isInterrupted()
                || (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0)) {
            throw new RunDeadlineExceededException();
        }
    }

    public synchronized void close() {
        closed = true;
        pendingFutures.forEach(future -> future.cancel(true));
    }

    public void setExecutionGuard(Runnable executionGuard) {
        this.executionGuard = java.util.Objects.requireNonNull(executionGuard);
    }

    /** Waiting is bounded by the remaining run budget, including executor queue time. */
    public <T> T await(CompletableFuture<T> future) {
        pendingFutures.add(future);
        try {
            checkActive();
            T result = deadlineNanos == Long.MAX_VALUE ? future.get()
                    : future.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            checkActive();
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RunDeadlineExceededException();
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new RunDeadlineExceededException();
        } catch (RunDeadlineExceededException error) {
            future.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("agent_execution_failed", error.getCause());
        } catch (CancellationException error) {
            checkActive();
            throw error;
        } finally {
            pendingFutures.remove(future);
            if (closed) future.cancel(true);
        }
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

    public synchronized void setRawProducts(List<Product> rawProducts) {
        this.rawProducts = rawProducts == null ? null : List.copyOf(rawProducts);
        candidateVersion.incrementAndGet();
        // Any result derived from the previous candidate set is now stale.
        rankedProducts = null;
        availableIds = null;
        finalProducts = null;
        copies = null;
        rankedCandidateVersion = -1;
        inventoryCandidateVersion = -1;
    }

    public List<Product> getRankedProducts() {
        return rankedProducts;
    }

    public void setRankedProducts(List<Product> rankedProducts) {
        this.rankedProducts = rankedProducts == null ? null : List.copyOf(rankedProducts);
        this.rankedCandidateVersion = candidateVersion.get();
    }

    public synchronized boolean setRankedProductsIfCurrent(long expectedCandidateVersion, List<Product> value) {
        return applyCandidatePatch(CandidateStatePatch.rerank(expectedCandidateVersion, value));
    }

    public Set<String> getAvailableIds() {
        return availableIds;
    }

    public void setAvailableIds(Set<String> availableIds) {
        this.availableIds = availableIds == null ? null : Set.copyOf(availableIds);
        this.inventoryCandidateVersion = candidateVersion.get();
    }

    public synchronized boolean applyInventoryIfCurrent(
            long expectedCandidateVersion,
            Set<String> value,
            Map<String, Object> fulfillmentValue) {
        return applyCandidatePatch(CandidateStatePatch.inventory(
                expectedCandidateVersion, value, fulfillmentValue));
    }

    /** Applies one child-agent patch only if its snapshot is still current and it owns the fields. */
    public synchronized boolean applyCandidatePatch(CandidateStatePatch patch) {
        checkActive();
        if (patch == null || candidateVersion.get() != patch.baseCandidateVersion()) return false;
        if (patch.producer() == AgentId.PRODUCT
                && patch.rankedProducts() != null
                && patch.availableIds() == null) {
            rankedProducts = patch.rankedProducts();
            rankedCandidateVersion = patch.baseCandidateVersion();
            return true;
        }
        if (patch.producer() == AgentId.INVENTORY
                && patch.availableIds() != null
                && patch.rankedProducts() == null) {
            availableIds = patch.availableIds();
            fulfillment = patch.fulfillment();
            inventoryCandidateVersion = patch.baseCandidateVersion();
            return true;
        }
        return false;
    }

    public List<Product> getFinalProducts() {
        return finalProducts;
    }

    public void setFinalProducts(List<Product> finalProducts) {
        this.finalProducts = finalProducts == null ? null : List.copyOf(finalProducts);
    }

    public synchronized boolean writeFinalProductsIfCurrent(AgentId agent, List<Product> value) {
        long current = candidateVersion.get();
        if (rankedCandidateVersion != current || inventoryCandidateVersion != current) return false;
        return write(agent, BlackboardField.FINAL_PRODUCTS, value).accepted();
    }

    public List<Map<String, String>> getCopies() {
        return copies;
    }

    public void setCopies(List<Map<String, String>> copies) {
        this.copies = copies == null ? null : List.copyOf(copies);
    }

    public Map<String, Object> getCampaignConstraints() { return campaignConstraints; }
    public void setCampaignConstraints(Map<String, Object> value) {
        this.campaignConstraints = value == null ? null : Map.copyOf(value);
    }
    public List<Map<String, Object>> getOrderContext() { return orderContext; }
    public void setOrderContext(List<Map<String, Object>> value) {
        this.orderContext = value == null ? null : List.copyOf(value);
    }
    public Set<String> getMarketEligibleIds() { return marketEligibleIds; }
    public void setMarketEligibleIds(Set<String> value) {
        this.marketEligibleIds = value == null ? null : Set.copyOf(value);
    }
    public Set<String> getFulfillmentEligibleIds() { return fulfillmentEligibleIds; }
    public void setFulfillmentEligibleIds(Set<String> value) {
        this.fulfillmentEligibleIds = value == null ? null : Set.copyOf(value);
    }
    public Map<String, Object> getFulfillment() { return fulfillment; }
    public void setFulfillment(Map<String, Object> value) {
        this.fulfillment = value == null ? Map.of() : Map.copyOf(value);
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
        return rawProducts != null && rankedProducts != null && availableIds != null && finalProducts != null
                && candidateDerivedResultsCurrent();
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

    public long getCandidateVersion() {
        return candidateVersion.get();
    }

    public boolean candidateDerivedResultsCurrent() {
        long current = candidateVersion.get();
        return rankedCandidateVersion == current && inventoryCandidateVersion == current;
    }

    public synchronized void incrementRecallAfterVetoCount() {
        recallAfterVetoCount++;
    }

    public boolean hasUnhandledVeto() {
        return vetoes.stream().anyMatch(veto -> !veto.isHandled());
    }

    public synchronized void markVetoesHandled() {
        vetoes.forEach(veto -> veto.setHandled(true));
    }

    public synchronized void clearAfterRerecall() {
        agentResults.remove(RecommendationPipelineExecutor.FULFILLMENT_RESULT);
        agentResults.remove(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT);
        agentResults.remove(RecommendationPipelineExecutor.RERANK_RESULT);
        agentResults.remove(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT);
        agentResults.remove(RecommendationPipelineExecutor.RETENTION_MARKETING_COPY_RESULT);
        marketEligibleIds = null;
        fulfillmentEligibleIds = null;
        fulfillment = Map.of();
        availableIds = null;
        rankedProducts = null;
        rankedCandidateVersion = -1;
        inventoryCandidateVersion = -1;
        finalProducts = null;
        copies = null;
        knownEvidenceIds.removeIf(id -> id.startsWith("product:")
                || id.startsWith("fulfillment:")
                || id.startsWith("inventory:")
                || id.startsWith("copy:"));
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
            case PROFILE -> AgentId.PROFILE;
            case RAW_PRODUCTS, RANKED_PRODUCTS -> AgentId.PRODUCT;
            case AVAILABLE_IDS, VETOES -> AgentId.INVENTORY;
            case FINAL_PRODUCTS -> AgentId.SUPERVISOR;
            case COPIES -> AgentId.COPY;
        };
    }

    /**
     * Agent-facing blackboard write. Trusted pipeline code may still use setters.
     * Copy cannot write any product list field.
     */
    @SuppressWarnings("unchecked")
    public synchronized BlackboardWriteResult write(AgentId agent, BlackboardField field, Object value) {
        checkActive();
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
