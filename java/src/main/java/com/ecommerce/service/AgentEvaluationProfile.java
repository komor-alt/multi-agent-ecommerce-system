package com.ecommerce.service;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Isolated benchmark-only runtime switches. No HTTP request can select these
 * options; production execution always observes {@link Settings#production()}.
 */
public final class AgentEvaluationProfile {
    public static final String ENABLE_PROPERTY = "ecom.agent.evaluation.enabled";
    private static final ThreadLocal<Settings> ACTIVE = new ThreadLocal<>();

    private AgentEvaluationProfile() {}

    public enum Topology { SINGLE, MULTI }

    public record Settings(Topology topology, boolean vetoEnabled, boolean pathGuardEnabled, long seed) {
        public Settings {
            Objects.requireNonNull(topology, "topology");
        }

        public static Settings production() {
            return new Settings(Topology.MULTI, true, true, 0L);
        }
    }

    public static Settings current() {
        Settings value = ACTIVE.get();
        return value == null ? Settings.production() : value;
    }

    public static <T> T run(Settings settings, Supplier<T> action) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("AGENT_EVALUATION_PROFILE_DISABLED");
        }
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(action, "action");
        Settings previous = ACTIVE.get();
        ACTIVE.set(settings);
        try {
            return action.get();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
}
