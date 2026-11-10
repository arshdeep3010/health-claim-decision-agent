package com.healthclaim.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Which engine judges a claim once the rules allow it.
 *
 * <ul>
 *   <li>{@code llm} (default): a chat LLM answering in a strict JSON schema
 *       ({@code MercuryDecisionProvider}, via the {@code app.mercury.*} chat settings).</li>
 *   <li>{@code decision-model}: a dedicated decision model (Parallel Decisions through
 *       OpenRouter's Decisions API) that returns a probability for each outcome.</li>
 * </ul>
 */
@ConfigurationProperties("app.decision-model")
public record DecisionModelProperties(
        @DefaultValue("llm") String engine,
        @DefaultValue("https://openrouter.ai/api") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("parallel/decisions-fast:free") String model,
        @DefaultValue("30000") int timeoutMs,
        @DefaultValue("2") int maxRetries
) {

    public boolean useDecisionModel() {
        return "decision-model".equalsIgnoreCase(engine) && StringUtils.hasText(apiKey);
    }
}
