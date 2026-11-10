package com.healthclaim.ai.metadata;

/**
 * Metadata captured for every AI call, for metrics and the audit trail. Contains
 * NO prompt content and NO chain-of-thought — only operational facts.
 */
public record AiCallMetadata(
        String provider,
        String model,
        String requestType,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        boolean success,
        int retryCount
) {
}
