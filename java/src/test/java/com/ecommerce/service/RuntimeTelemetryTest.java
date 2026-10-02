package com.ecommerce.service;

import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.runtime.persistence.RecommendationPersistenceMonitor;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeTelemetryTest {
    @Test
    void metricsBoundLabelsAndExposeDatabaseBacklog() {
        var meters = new SimpleMeterRegistry();
        var persistence = mock(RecommendationPersistenceMonitor.class);
        var events = mock(RecommendationRunEventService.class);
        when(events.snapshot()).thenReturn(Map.of());
        when(persistence.snapshot()).thenReturn(Map.of("outboxInFlight", 3L,
                "outboxOldestUnfinishedAgeSeconds", 45L));
        var telemetry = new RuntimeTelemetry(meters, persistence, new AgentConcurrencyGuard(4), events);
        telemetry.recordRun("untrusted-user-input", null, 100, 2);
        telemetry.recordTool(ToolCallRecord.builder().toolName("attacker-controlled").latencyMs(1).build());
        telemetry.refreshPersistence();
        assertEquals(1, meters.get("agent.runs").tags("scene", "other", "outcome", "failed").counter().count());
        assertEquals(3, meters.get("agent.persistence").tag("state", "outboxInFlight").gauge().value());
        assertEquals(45, meters.get("agent.persistence").tag("state", "outboxOldestUnfinishedAgeSeconds").gauge().value());
        assertTrue(meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("untrusted") || tag.getValue().contains("attacker")));
        meters.close();
    }
}
