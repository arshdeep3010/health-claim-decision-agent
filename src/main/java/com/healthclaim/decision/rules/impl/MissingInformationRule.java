package com.healthclaim.decision.rules.impl;

import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mandatory information must be present. Missing diagnosis or procedure routes
 * the claim to human review rather than letting the AI guess.
 */
@Component
public class MissingInformationRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        List<String> missing = new ArrayList<>();
        if (inputs.facts().knownValue(FactKey.DIAGNOSIS).isEmpty()) {
            missing.add("diagnosis");
        }
        if (inputs.facts().knownValue(FactKey.PROCEDURE).isEmpty()) {
            missing.add("procedure");
        }
        if (inputs.claim().claimAmount() == null) {
            missing.add("claimAmount");
        }
        if (!missing.isEmpty()) {
            return RuleResult.review("MISSING_REQUIRED_INFORMATION",
                    "Required information is missing or unknown: " + String.join(", ", missing) + ".");
        }
        return RuleResult.allow("REQUIRED_INFORMATION_PRESENT", "All required information is present.");
    }
}
