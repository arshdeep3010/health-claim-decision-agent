package com.healthclaim.decision.rules.impl;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import org.springframework.stereotype.Component;

/**
 * When the pre-existing-condition status is unknown, the claim cannot be safely
 * auto-approved and is routed to review (per the decision policy).
 */
@Component
public class PreExistingConditionRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        FactStatus status = inputs.facts().status(FactKey.PRE_EXISTING_CONDITION);
        if (status != FactStatus.KNOWN) {
            return RuleResult.review("PRE_EXISTING_STATUS_UNKNOWN",
                    "Pre-existing disease declaration status is uncertain.");
        }
        return RuleResult.allow("PRE_EXISTING_STATUS_KNOWN", "Pre-existing condition status is known.");
    }
}
