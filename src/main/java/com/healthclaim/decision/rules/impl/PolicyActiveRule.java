package com.healthclaim.decision.rules.impl;

import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.rules.ClaimRule;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.policy.domain.PolicyStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Hard constraint: the policy must be active and in force on the service date.
 */
@Component
public class PolicyActiveRule implements ClaimRule {

    @Override
    public RuleResult evaluate(DecisionInputs inputs) {
        PolicyFacts policy = inputs.policy();
        LocalDate serviceDate = inputs.claim().serviceDate();

        if (policy.status() == PolicyStatus.CANCELLED) {
            return RuleResult.reject("POLICY_CANCELLED", "Policy is cancelled.");
        }
        if (policy.status() == PolicyStatus.EXPIRED) {
            return RuleResult.reject("POLICY_EXPIRED", "Policy status is expired.");
        }
        if (policy.expiryDate() != null && serviceDate.isAfter(policy.expiryDate())) {
            return RuleResult.reject("POLICY_EXPIRED",
                    "Service date " + serviceDate + " is after policy expiry " + policy.expiryDate() + ".");
        }
        if (policy.effectiveDate() != null && serviceDate.isBefore(policy.effectiveDate())) {
            return RuleResult.reject("POLICY_NOT_EFFECTIVE",
                    "Service date " + serviceDate + " is before policy effective date " + policy.effectiveDate() + ".");
        }
        return RuleResult.allow("POLICY_ACTIVE", "Policy is active and in force on the service date.");
    }
}
