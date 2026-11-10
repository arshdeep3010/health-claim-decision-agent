package com.healthclaim.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate limiting for the expensive (AI-backed) endpoints. Defaults to a per-caller
 * allowance of {@code limitForPeriod} requests per {@code refreshPeriodSeconds}.
 */
@ConfigurationProperties("app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30") int limitForPeriod,
        @DefaultValue("60") long refreshPeriodSeconds
) {
}
