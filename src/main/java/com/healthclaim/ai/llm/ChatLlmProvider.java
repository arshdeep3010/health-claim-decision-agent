package com.healthclaim.ai.llm;

import com.healthclaim.ai.exception.AiProviderException;

/**
 * Abstraction over a chat LLM. Implementations are selected by configuration so
 * no business logic depends on a specific provider. Controllers never call this
 * directly (only services/orchestrators do).
 */
public interface ChatLlmProvider {

    /**
     * Executes a chat completion.
     *
     * @throws AiProviderException if the provider is unavailable, times out, or
     *                             returns an unusable response. Callers treat this
     *                             as a signal to route to HUMAN_REVIEW, never to
     *                             silently approve/reject.
     */
    ChatResponse chat(ChatRequest request);

    /** Short provider identifier (e.g. "openrouter", "noop"), for audit/metadata. */
    String providerId();

    /** Whether a usable provider is configured (false for the no-op default). */
    default boolean isConfigured() {
        return true;
    }
}
