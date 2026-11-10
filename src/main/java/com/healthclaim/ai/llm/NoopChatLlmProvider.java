package com.healthclaim.ai.llm;

import com.healthclaim.ai.exception.AiProviderException;

/**
 * Default provider used when no real AI provider is configured (dev/CI without
 * keys). Every call fails clearly so the decision layer routes to HUMAN_REVIEW
 * rather than guessing.
 */
public class NoopChatLlmProvider implements ChatLlmProvider {

    @Override
    public ChatResponse chat(ChatRequest request) {
        throw new AiProviderException("No AI provider is configured (MERCURY_PROVIDER=noop)");
    }

    @Override
    public String providerId() {
        return "noop";
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
