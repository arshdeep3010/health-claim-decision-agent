package com.healthclaim.decision.rules.impl;

import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A claim amount exceeding the coverage limit needs a human to decide on partial
 * settlement — routed to review rather than auto-rejected.
 */
@Component
public class CoverageLimitRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        BigDecimal limit = inputs.policy().coverageLimit();
        BigDecimal amount = inputs.claim().claimAmount();
        if (limit == null || amount == null) {
            return RuleResult.notApplicable("COVERAGE_LIMIT_CHECK");
        }
        if (amount.compareTo(limit) > 0) {
            return RuleResult.review("CLAIM_EXCEEDS_COVERAGE_LIMIT",
                    "Claim amount " + amount + " exceeds coverage limit " + limit + ".");
        }
        return RuleResult.allow("WITHIN_COVERAGE_LIMIT", "Claim amount is within the coverage limit.");
    }
}
