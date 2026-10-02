package com.ecommerce.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:operational-endpoints;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "agent.security.internal-api.enabled=true",
        "agent.security.internal-api.token=operational-test-token-at-least-32-bytes",
        "spring.data.redis.port=1",
        "agent.persistence.outbox-scan-ms=600000"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
class OperationalEndpointsIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void livenessAndDatabaseReadinessArePublicWithoutOptionalRedisDependency() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void metricsRequireServiceCredentialsAndDoNotExposeRequestIdentifiers() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")
                        .header("X-Internal-Service-Token", "operational-test-token-at-least-32-bytes"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("agent_runs_capacity")))
                .andExpect(content().string(containsString("agent_persistence")));
    }
}
