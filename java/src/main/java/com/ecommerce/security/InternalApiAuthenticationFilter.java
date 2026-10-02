package com.ecommerce.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;

/** Enforces the Gateway -> Java trust boundary, including diagnostics and SSE endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Service-Token";
    private static final Set<String> PUBLIC_HEALTH_PATHS = Set.of(
            "/health", "/api/v1/health", "/actuator/health",
            "/actuator/health/liveness", "/actuator/health/readiness");
    private final boolean enabled;
    private final byte[] expectedDigest;

    public InternalApiAuthenticationFilter(InternalApiSecurityProperties properties, Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (production && !properties.isEnabled()) {
            throw new IllegalStateException("Internal API authentication must be enabled in production");
        }
        this.enabled = properties.isEnabled();
        String token = properties.getToken() == null ? "" : properties.getToken();
        if (enabled && token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Internal API token must contain at least 32 bytes");
        }
        this.expectedDigest = digest(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Default deny also covers new controllers and servlet path normalization variants.
        return PUBLIC_HEALTH_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(HEADER));
        boolean valid = values.size() == 1 && values.get(0).length() <= 4096
                && MessageDigest.isEqual(expectedDigest, digest(values.get(0)));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"INTERNAL_AUTH_REQUIRED\",\"message\":\"Valid service credentials required\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
