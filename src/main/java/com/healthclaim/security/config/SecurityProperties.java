package com.healthclaim.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Security configuration: CORS origins and JWT signing/expiry.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("http://localhost:3000") List<String> corsAllowedOrigins,
        @DefaultValue Jwt jwt
) {
    public record Jwt(
            @DefaultValue("dev-only-insecure-secret-change-me-0123456789-abcdefghijklmnop") String secret,
            @DefaultValue("120") long expirationMinutes,
            @DefaultValue("health-claim-decision-agent") String issuer
    ) {
    }
}
