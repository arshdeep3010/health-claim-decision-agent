package com.healthclaim.decision.provider;

import com.healthclaim.decision.context.ClaimDecisionContext;

/**
 * Produces a semantic judgment for a claim. Implementations are selected by
 * configuration (Mercury when configured, rule-only otherwise). The orchestrator
 * — not this provider — owns the final decision and enforces hard constraints.
 */
public interface DecisionProvider {

    AiDecision decide(ClaimDecisionContext context);

    String providerId();
}
