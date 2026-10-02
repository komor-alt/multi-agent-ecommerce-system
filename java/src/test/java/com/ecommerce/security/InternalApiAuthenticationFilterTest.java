package com.ecommerce.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiAuthenticationFilterTest {
    private static final String TOKEN = "test-internal-service-token-at-least-32-bytes";

    @Test
    void rejectsDirectAccessEvenWithForgedOperatorHeader() throws Exception {
        var filter = securedFilter();
        var request = new MockHttpServletRequest("POST", "/api/v1/after-sales/proposals/123/approve");
        request.addHeader("X-Authenticated-Operator", "forged-admin");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsOnlySingleValidTokenAndProtectsMetrics() throws Exception {
        for (String path : new String[]{"/api/v1/agent-runs/r1/events", "/metrics", "/actuator/prometheus", "/actuator/env", "/api;ignored/v1/orders", "/new-endpoint"}) {
            var missing = new MockHttpServletResponse();
            securedFilter().doFilter(new MockHttpServletRequest("GET", path), missing, new MockFilterChain());
            assertThat(missing.getStatus()).isEqualTo(401);
            var request = new MockHttpServletRequest("GET", path);
            request.addHeader(InternalApiAuthenticationFilter.HEADER, TOKEN);
            var chain = new MockFilterChain();
            securedFilter().doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
        var duplicate = new MockHttpServletRequest("GET", "/api/v1/orders");
        duplicate.addHeader(InternalApiAuthenticationFilter.HEADER, TOKEN);
        duplicate.addHeader(InternalApiAuthenticationFilter.HEADER, "forged");
        var response = new MockHttpServletResponse();
        securedFilter().doFilter(duplicate, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void healthProbesStayPublicAndWrongTokensFail() throws Exception {
        for (String path : new String[]{"/api/v1/health", "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"}) {
            var chain = new MockFilterChain();
            securedFilter().doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
        var request = new MockHttpServletRequest("GET", "/api/v1/after-sales/tickets");
        request.addHeader(InternalApiAuthenticationFilter.HEADER, "wrong-token-that-is-also-longer-than-32");
        var response = new MockHttpServletResponse();
        securedFilter().doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void productionCannotStartWithoutAuthenticationOrStrongSecret() {
        var environment = new MockEnvironment().withProperty("spring.profiles.active", "production");
        environment.setActiveProfiles("production");
        var properties = new InternalApiSecurityProperties();
        assertThatThrownBy(() -> new InternalApiAuthenticationFilter(properties, environment)).isInstanceOf(IllegalStateException.class);
        properties.setEnabled(true);
        properties.setToken("short");
        assertThatThrownBy(() -> new InternalApiAuthenticationFilter(properties, environment)).isInstanceOf(IllegalStateException.class);
    }

    private InternalApiAuthenticationFilter securedFilter() {
        var properties = new InternalApiSecurityProperties();
        properties.setEnabled(true);
        properties.setToken(TOKEN);
        return new InternalApiAuthenticationFilter(properties, new MockEnvironment());
    }
}
