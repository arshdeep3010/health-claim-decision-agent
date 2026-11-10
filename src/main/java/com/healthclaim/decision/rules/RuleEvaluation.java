package com.healthclaim.decision.rules;

import java.util.List;

/**
 * The outcome of running all rules: the full result list (for audit) and the
 * aggregate deterministic verdict.
 */
public record RuleEvaluation(List<RuleResult> results, DeterministicOutcome outcome) {

    /** Reason codes for the rules that drove the aggregate outcome. */
    public List<String> drivingReasonCodes() {
        RuleOutcome target = switch (outcome) {
            case REJECT -> RuleOutcome.REJECT;
            case REVIEW -> RuleOutcome.REVIEW;
            case ALLOW -> RuleOutcome.ALLOW;
        };
        return results.stream()
                .filter(r -> r.outcome() == target)
                .map(RuleResult::code)
                .toList();
    }
}
