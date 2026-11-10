package com.healthclaim.decision.provider;

import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.domain.DecisionType;

import java.util.List;

/**
 * Baseline provider used when no AI chat provider is configured (dev/CI) and as
 * the "rule-only" arm in the evaluation framework. It is only consulted when the
 * deterministic rules already found no objection (ALLOW), so it simply concurs
 * with APPROVE and reports no confidence (none was computed).
 */
public class RuleOnlyDecisionProvider implements DecisionProvider {

    @Override
    public AiDecision decide(ClaimDecisionContext context) {
        return new AiDecision(
                DecisionType.APPROVE,
                null,
                List.of("RULES_ONLY_NO_OBJECTION"),
                "Approved by deterministic rules; no AI semantic review was performed.",
                List.of(),
                providerId(),
                "none",
                0L,
                0);
    }

    @Override
    public String providerId() {
        return "rule-only";
    }
}
