package com.ecommerce.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeLimitsPropertiesValidationTest {
    @Test
    void defaultsAndPositiveOverridesAreAccepted() {
        assertThatCode(new RuntimeLimitsProperties()::validate).doesNotThrowAnyException();
        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        limits.setRunTimeout(Duration.ofSeconds(2));
        limits.setReadTimeout(Duration.ofMillis(100));
        limits.setConnectTimeout(Duration.ofMillis(50));
        limits.setMaxSteps(1);
        limits.setMaxItems(1);
        assertThatCode(limits::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidOverrides")
    void invalidConfiguredLimitsFailAtStartup(Consumer<RuntimeLimitsProperties> override) {
        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        override.accept(limits);
        assertThatThrownBy(limits::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.limits");
    }

    static Stream<Consumer<RuntimeLimitsProperties>> invalidOverrides() {
        return Stream.of(
                limits -> limits.setRunTimeout(null),
                limits -> limits.setRunTimeout(Duration.ZERO),
                limits -> limits.setRunTimeout(Duration.ofSeconds(-1)),
                limits -> limits.setConnectTimeout(null),
                limits -> limits.setConnectTimeout(Duration.ZERO),
                limits -> limits.setConnectTimeout(Duration.ofSeconds(-1)),
                limits -> limits.setReadTimeout(null),
                limits -> limits.setReadTimeout(Duration.ZERO),
                limits -> limits.setReadTimeout(Duration.ofSeconds(-1)),
                limits -> limits.setMaxSteps(0),
                limits -> limits.setMaxItems(0));
    }
}
