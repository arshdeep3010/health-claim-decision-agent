package com.healthclaim.decision.rules;

import com.healthclaim.decision.context.DecisionInputs;

/**
 * A deterministic business rule. Rules are pure functions of {@link DecisionInputs}
 * (no DB, no external calls), which makes them trivially unit-testable and the
 * decision reproducible.
 */
public interface ClaimRule {

    RuleResult evaluate(DecisionInputs inputs);
}
