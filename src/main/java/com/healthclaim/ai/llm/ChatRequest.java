package com.healthclaim.ai.llm;

import java.util.List;

/**
 * A typed chat request. Only controlled fields cross the provider boundary; no
 * application/domain objects are passed through directly.
 *
 * @param messages      the conversation
 * @param jsonSchema    optional structured-output schema (null = free text)
 * @param maxTokens     optional output cap
 * @param temperature   optional sampling temperature
 * @param requestType   label for metrics/audit (e.g. "claim-extraction")
 */
public record ChatRequest(
        List<ChatMessage> messages,
        JsonSchemaSpec jsonSchema,
        Integer maxTokens,
        Double temperature,
        String requestType
) {
    public static ChatRequest of(List<ChatMessage> messages, String requestType) {
        return new ChatRequest(messages, null, null, null, requestType);
    }

    public ChatRequest withSchema(JsonSchemaSpec schema) {
        return new ChatRequest(messages, schema, maxTokens, temperature, requestType);
    }
}
