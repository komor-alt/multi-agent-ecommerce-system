package com.ecommerce.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Credentials are injected through deployment secrets, never through model/tool arguments. */
@Component
@ConfigurationProperties(prefix = "agent.security.internal-api")
public class InternalApiSecurityProperties {
    private boolean enabled;
    private String token = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
