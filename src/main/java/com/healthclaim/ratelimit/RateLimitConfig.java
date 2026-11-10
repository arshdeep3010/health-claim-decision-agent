package com.healthclaim.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Registers the rate-limiting interceptor on the expensive AI-backed endpoints:
 * claim analyze/extract/explain and policy ingest.
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitingInterceptor interceptor;

    public RateLimitConfig(RateLimitProperties properties, ObjectMapper objectMapper) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(properties.limitForPeriod())
                .limitRefreshPeriod(Duration.ofSeconds(properties.refreshPeriodSeconds()))
                .timeoutDuration(Duration.ZERO) // non-blocking: reject immediately when exhausted
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        this.interceptor = new RateLimitingInterceptor(registry, properties, objectMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/v1/claims/*/analyze",
                "/api/v1/claims/*/analyze/stream",
                "/api/v1/claims/*/extract",
                "/api/v1/claims/*/explain",
                "/api/v1/policies/*/ingest");
    }
}
