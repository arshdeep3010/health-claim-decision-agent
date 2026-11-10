package com.healthclaim.decision.rules.impl;

import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.claim.extraction.FactKey;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Conflicting extracted facts cannot be auto-decided and route to human review.
 */
@Component
public class ConflictingFactsRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        if (inputs.facts().anyConflicting()) {
            String keys = inputs.facts().conflictingKeys().stream()
                    .map(FactKey::wire)
                    .collect(Collectors.joining(", "));
            return RuleResult.review("CONFLICTING_INFORMATION",
                    "Conflicting information for: " + keys + ".");
        }
        return RuleResult.allow("NO_CONFLICTING_INFORMATION", "No conflicting facts detected.");
    }
}
