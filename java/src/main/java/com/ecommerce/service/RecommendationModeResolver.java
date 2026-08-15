package com.ecommerce.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Recommendation LLM mode.
 * <ul>
 *   <li>AUTO — opt-in only; uses the LLM only when live mode is enabled and a real API key is configured.</li>
 *   <li>LLM — explicit live mode, still requiring a real API key and live-enabled=true.</li>
 *   <li>RULES — deterministic and offline; the production default.</li>
 * </ul>
 * The scene path itself is always enforced server-side regardless of mode.
 */
@Service
public class RecommendationModeResolver {

    public enum Mode { AUTO, LLM, RULES }

    private final Mode mode;
    private final boolean hasApiKey;
    private final boolean liveEnabled;

    /** Direct construction is reserved for explicit tests; Spring production wiring is fail-closed. */
    public RecommendationModeResolver(String mode, String apiKey) {
        this(mode, apiKey, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RecommendationModeResolver(@Value("${agent.recommend.mode:RULES}") String mode,
                                      @Value("${ECOM_LLM_API_KEY:your_api_key_here}") String apiKey,
                                      @Value("${agent.recommend.live-enabled:false}") boolean liveEnabled) {
        this.mode = parseMode(mode);
        this.hasApiKey = apiKey != null && !apiKey.isBlank() && !"your_api_key_here".equals(apiKey);
        this.liveEnabled = liveEnabled;
    }

    public boolean llmEnabled() {
        return liveEnabled && hasApiKey && (mode == Mode.LLM || mode == Mode.AUTO);
    }

    public Mode mode() {
        return mode;
    }

    public boolean liveEnabled() {
        return liveEnabled;
    }

    private Mode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return Mode.RULES;
        }
        try {
            return Mode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Mode.RULES;
        }
    }
}