package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Marketing copy Agent: localized prompt + personalized copy + compliance check.
 */
@Component
public class MarketingCopyAgent extends BaseAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendationModeResolver modeResolver;

    private static final Map<String, String> TEMPLATES = Map.of(
            "new_user", "welcome new shoppers and highlight first-order value",
            "high_value", "use a premium, service-focused tone",
            "price_sensitive", "highlight value, bundles, and transparent shipping",
            "active", "focus on product use cases and cross-border delivery clarity",
            "churn_risk", "use a friendly win-back tone with practical benefits"
    );

    private static final List<String> FORBIDDEN_ZH = List.of("最好", "第一", "国家级", "全球首", "绝对", "100%", "永久", "万能");
    private static final List<String> FORBIDDEN_EN = List.of("best ever", "number one", "guaranteed", "100%", "forever", "miracle");

    public MarketingCopyAgent(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, new RecommendationModeResolver("RULES", ""));
    }

    @Autowired
    public MarketingCopyAgent(ChatClient.Builder chatClientBuilder, RecommendationModeResolver modeResolver) {
        super("marketing_copy", 10.0, 1);
        this.chatClient = chatClientBuilder.build();
        this.modeResolver = modeResolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        UserProfile profile = (UserProfile) params.get("userProfile");
        List<Product> products = (List<Product>) params.getOrDefault("products", List.of());

        if (products.isEmpty()) {
            return AgentResult.builder().agentName(name).success(true)
                    .data(Map.of("copies", List.of(), "copy_locale", request.localeOrDefault()))
                    .confidence(1.0).build();
        }

        String templateKey = selectTemplate(profile);
        String systemPrompt = localizedSystemPrompt(request, templateKey);
        String productInfo = products.stream()
                .map(p -> String.format("ID:%s name:%s price:%s %.2f country:%s warehouse:%s deliveryDays:%d tags:%s",
                        p.getProductId(), p.getName(), p.getCurrency(), p.getPrice(), request.countryOrDefault(),
                        p.getWarehouseRegion(), p.getDeliveryDays(), p.getTags()))
                .collect(Collectors.joining("\n"));

        List<Map<String, String>> copies;
        try {
            if (!modeResolver.llmEnabled()) throw new IllegalStateException("rules_mode");
            LlmCallBudget budget = params.get("llmBudget") instanceof LlmCallBudget value ? value : null;
            if (budget == null || !budget.tryAcquire(name, String.valueOf(params.getOrDefault("llmFingerprint", request.getUserId())))) {
                throw new IllegalStateException("llm_budget_exhausted");
            }
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user("Products:\n" + productInfo)
                    .call()
                    .content();
            copies = parseCopies(response);
            if (copies.isEmpty()) {
                copies = fallbackCopies(products, request);
            }
        } catch (Exception e) {
            log.warn("Localized copy LLM failed, using template fallback: {}", e.getMessage());
            copies = fallbackCopies(products, request);
        }

        copies = copies.stream()
                .map(item -> complianceCheck(item, request.localeOrDefault()))
                .map(item -> addLocale(item, request.localeOrDefault()))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("copies", copies);
        data.put("template_used", templateKey);
        data.put("copy_locale", request.localeOrDefault());
        data.put("cross_border_context", Map.of(
                "country", request.countryOrDefault(),
                "locale", request.localeOrDefault(),
                "currency", request.currencyOrDefault(),
                "platform", request.platformOrDefault()
        ));

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(0.9)
                .build();
    }

    private String localizedSystemPrompt(RecommendationRequest request, String templateKey) {
        String language = languageInstruction(request.localeOrDefault());
        return String.format("""
                You are a localized cross-border ecommerce marketing copy agent.
                Locale=%s, country=%s, currency=%s, platform=%s.
                Write in: %s.
                Tone instruction: %s.
                Mention local currency and delivery estimate when useful. Do not invent product IDs, warehouse, delivery days, discounts, or claims.
                Return JSON array only: [{"product_id":"xxx","copy":"localized copy"}]
                """,
                request.localeOrDefault(), request.countryOrDefault(), request.currencyOrDefault(), request.platformOrDefault(),
                language, TEMPLATES.getOrDefault(templateKey, TEMPLATES.get("active")));
    }

    private String languageInstruction(String locale) {
        if (locale.startsWith("zh")) return "Simplified Chinese";
        if (locale.startsWith("ms")) return "Malay";
        if (locale.startsWith("th")) return "Thai";
        return "English";
    }

    private String selectTemplate(UserProfile profile) {
        if (profile == null || profile.getSegments() == null) return "active";
        List<String> priority = List.of("new_user", "high_value", "churn_risk", "price_sensitive", "active");
        for (String seg : priority) {
            if (profile.getSegments().contains(seg)) return seg;
        }
        return "active";
    }

    private List<Map<String, String>> parseCopies(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse copies: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, String>> fallbackCopies(List<Product> products, RecommendationRequest request) {
        return products.stream()
                .map(product -> Map.of(
                        "product_id", product.getProductId(),
                        "copy", fallbackCopy(product, request),
                        "locale", request.localeOrDefault()
                ))
                .collect(Collectors.toList());
    }

    private String fallbackCopy(Product product, RecommendationRequest request) {
        if (request.localeOrDefault().startsWith("zh")) {
            return String.format("%s 现以 %s %.2f 推荐给%s用户，预计%d天送达，适合日常跨境选购。",
                    product.getName(), product.getCurrency(), product.getPrice(), request.countryOrDefault(), product.getDeliveryDays());
        }
        return String.format("%s is available for %s %.2f in %s, with an estimated %d-day delivery window.",
                product.getName(), product.getCurrency(), product.getPrice(), request.countryOrDefault(), product.getDeliveryDays());
    }

    private Map<String, String> complianceCheck(Map<String, String> copyItem, String locale) {
        String text = copyItem.getOrDefault("copy", "");
        List<String> forbidden = locale.startsWith("zh") ? FORBIDDEN_ZH : FORBIDDEN_EN;
        for (String word : forbidden) {
            text = text.replace(word, "***");
        }
        Map<String, String> result = new HashMap<>(copyItem);
        result.put("copy", text);
        return result;
    }

    private Map<String, String> addLocale(Map<String, String> copyItem, String locale) {
        Map<String, String> result = new HashMap<>(copyItem);
        result.put("locale", locale);
        return result;
    }

    private RecommendationRequest requestFrom(Map<String, Object> params) {
        Object request = params.get("request");
        if (request instanceof RecommendationRequest recommendationRequest) {
            return recommendationRequest;
        }
        return RecommendationRequest.builder().userId(String.valueOf(params.getOrDefault("userId", "anonymous"))).build();
    }
}
