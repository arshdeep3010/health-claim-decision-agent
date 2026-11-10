package com.healthclaim.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mercury chat-provider configuration. {@code provider} selects the implementation
 * ("openrouter"/"openai-compatible"/"inception" use the OpenAI-compatible client;
 * anything else, incl. "noop", uses the no-op provider so dev/CI need no keys).
 */
@ConfigurationProperties("app.mercury")
public record MercuryProperties(
        @DefaultValue("noop") String provider,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String model,
        @DefaultValue("30000") int timeoutMs,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("300") long retryBackoffMs,
        @DefaultValue("v1") String promptVersion
) {
    public boolean isOpenAiCompatible() {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "openrouter", "openai-compatible", "inception", "mercury" -> true;
            default -> false;
        };
    }
}
