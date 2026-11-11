package com.healthclaim.decision.dto;

import java.util.UUID;

/**
 * @param source "llm" when the chat model wrote it, "template" when the deterministic
 *               fallback did (offline, or the model failed or timed out)
 * @param model  the chat model that wrote it, when {@code source} is "llm"
 */
public record ExplanationResponse(UUID claimId, String decision, String explanation, String source, String model) {
}
