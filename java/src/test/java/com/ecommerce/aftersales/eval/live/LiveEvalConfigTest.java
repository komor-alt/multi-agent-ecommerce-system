package com.ecommerce.aftersales.eval.live;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline unit tests for Live Eval configuration validation (no API calls).
 */
class LiveEvalConfigTest {

    @Test
    void runEnabledOnlyForExactTrueValue() {
        // 与 @EnabledIfEnvironmentVariable(matches = "(?i)true") 语义一致：忽略大小写、整词匹配。
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "true"))).isTrue();
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "TRUE"))).isTrue();
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "True"))).isTrue();
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "  true  "))).isTrue();
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "false"))).isFalse();
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "1"))).isFalse();
        // 整词匹配：Boolean.parseBoolean 只认 exactly "true"（忽略大小写），"truex" 必须拒绝。
        assertThat(LiveEvalConfig.runEnabled(Map.of(LiveEvalConfig.RUN_ENV, "truex"))).isFalse();
        assertThat(LiveEvalConfig.runEnabled(Map.of())).isFalse();
    }

    @Test
    void requireApiKeyAcceptsRealKey() {
        // 假 key 用显然非真实的 test-api-key，避免与真实密钥混淆。
        String key = LiveEvalConfig.requireApiKey(Map.of(
                LiveEvalConfig.RUN_ENV, "true",
                LiveEvalConfig.API_KEY_ENV, "test-api-key"));
        assertThat(key).isEqualTo("test-api-key");
    }

    @Test
    void requireApiKeyFailsClearlyWhenMissing() {
        assertThatThrownBy(() -> LiveEvalConfig.requireApiKey(Map.of(
                LiveEvalConfig.RUN_ENV, "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LiveEvalConfig.API_KEY_ENV)
                .hasMessageContaining("never silently fall back to rules");
    }

    @Test
    void requireApiKeyFailsClearlyForPlaceholder() {
        assertThatThrownBy(() -> LiveEvalConfig.requireApiKey(Map.of(
                LiveEvalConfig.RUN_ENV, "true",
                LiveEvalConfig.API_KEY_ENV, LiveEvalConfig.PLACEHOLDER_API_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void requireApiKeyFailsClearlyForBlank() {
        assertThatThrownBy(() -> LiveEvalConfig.requireApiKey(Map.of(
                LiveEvalConfig.RUN_ENV, "true",
                LiveEvalConfig.API_KEY_ENV, "   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LiveEvalConfig.API_KEY_ENV);
    }
}
