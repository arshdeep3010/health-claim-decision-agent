package com.healthclaim.decision.provider;

import com.healthclaim.decision.context.ClaimDecisionContext;

/**
 * Produces a semantic judgment for a claim. Implementations are selected by
 * configuration (a decision model, a chat LLM, or rule-only offline). The
 * orchestrator — not this provider — owns the final decision and enforces hard
 * constraints.
 */
public interface DecisionProvider {

    AiDecision decide(ClaimDecisionContext context);

    String providerId();

    /** What the engine is asked to do, shown alongside its input in the live stream. */
    default String instructions() {
        return DecisionPromptPayload.INSTRUCTIONS;
    }

    /** The model that will answer, if known before the call (for display). */
    default String modelName() {
        return null;
    }

    /** "llm", "decision-model" or "rules", for display. */
    default String engine() {
        return "llm";
    }
}
