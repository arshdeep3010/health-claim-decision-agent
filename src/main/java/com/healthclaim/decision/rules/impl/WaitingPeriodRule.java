package com.healthclaim.decision.rules.impl;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Hard constraint: a claim whose service date falls within an applicable waiting
 * period is not payable. Handles a general/initial waiting period, and a
 * pre-existing-condition waiting period when the claim is flagged pre-existing.
 */
@Component
public class WaitingPeriodRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        PolicyFacts policy = inputs.policy();
        Map<String, Integer> waits = policy.waitingPeriodMonths();
        if (waits == null || waits.isEmpty() || policy.effectiveDate() == null) {
            return RuleResult.notApplicable("WAITING_PERIOD_CHECK");
        }
        LocalDate serviceDate = inputs.claim().serviceDate();

        Integer general = firstNonNull(waits.get("GENERAL"), waits.get("INITIAL"));
        if (general != null && serviceDate.isBefore(policy.effectiveDate().plusMonths(general))) {
            return RuleResult.reject("WAITING_PERIOD_VIOLATION",
                    "Service date is within the " + general + "-month initial waiting period.");
        }

        Integer preExisting = waits.get("PRE_EXISTING");
        boolean isPreExisting = inputs.facts().status(FactKey.PRE_EXISTING_CONDITION) == FactStatus.KNOWN
                && inputs.facts().knownValue(FactKey.PRE_EXISTING_CONDITION)
                .map("true"::equalsIgnoreCase).orElse(false);
        if (preExisting != null && isPreExisting
                && serviceDate.isBefore(policy.effectiveDate().plusMonths(preExisting))) {
            return RuleResult.reject("PRE_EXISTING_WAITING_PERIOD",
                    "Pre-existing condition claim is within the " + preExisting + "-month waiting period.");
        }
        return RuleResult.allow("WAITING_PERIOD_SATISFIED", "Applicable waiting periods are satisfied.");
    }

    private static Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }
}
