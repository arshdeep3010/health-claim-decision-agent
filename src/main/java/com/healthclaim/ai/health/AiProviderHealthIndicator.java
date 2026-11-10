package com.healthclaim.ai.health;

import com.healthclaim.ai.llm.ChatLlmProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether an AI chat provider is configured. Deliberately does NOT make a
 * live (billable) call and never reports DOWN for a transient provider outage, so
 * it cannot take down liveness. It is a component indicator only; the liveness and
 * readiness probe groups are unaffected.
 */
@Component("aiProvider")
public class AiProviderHealthIndicator implements HealthIndicator {

    private final ChatLlmProvider provider;

    public AiProviderHealthIndicator(ChatLlmProvider provider) {
        this.provider = provider;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("provider", provider.providerId())
                .withDetail("configured", provider.isConfigured())
                .build();
    }
}
