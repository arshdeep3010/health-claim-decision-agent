package com.healthclaim.ai.llm;

/**
 * A typed chat response. Token counts are populated only when the provider
 * returns usage; they are never fabricated.
 */
public record ChatResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String finishReason,
        long latencyMs,
        int retryCount
) {
}
