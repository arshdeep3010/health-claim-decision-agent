package com.healthclaim.decision.rules.impl;

import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Hard constraint: a procedure/diagnosis explicitly excluded by the policy is
 * rejected. Matching is a case-insensitive substring check against the policy's
 * structured exclusion list.
 */
@Component
public class ExclusionRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        if (inputs.policy().exclusions() == null || inputs.policy().exclusions().isEmpty()) {
            return RuleResult.notApplicable("EXCLUSION_CHECK");
        }
        ClaimSummary claim = inputs.claim();
        String haystack = (nullToEmpty(claim.procedureName()) + " "
                + nullToEmpty(claim.diagnosis()) + " "
                + nullToEmpty(claim.treatment())).toLowerCase(Locale.ROOT);

        for (String exclusion : inputs.policy().exclusions()) {
            if (exclusion == null || exclusion.isBlank()) {
                continue;
            }
            if (haystack.contains(exclusion.toLowerCase(Locale.ROOT).strip())) {
                return RuleResult.reject("PROCEDURE_EXCLUDED",
                        "Claim matches policy exclusion: \"" + exclusion.strip() + "\".");
            }
        }
        return RuleResult.allow("NO_EXCLUSION_MATCH", "No matching policy exclusion found.");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
