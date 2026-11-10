package com.healthclaim.decision.rules;

/**
 * The result of evaluating one rule: a stable reason code, the outcome, and a
 * human-readable message (safe to surface — no sensitive data).
 */
public record RuleResult(String code, RuleOutcome outcome, String message) {

    public static RuleResult allow(String code, String message) {
        return new RuleResult(code, RuleOutcome.ALLOW, message);
    }

    public static RuleResult reject(String code, String message) {
        return new RuleResult(code, RuleOutcome.REJECT, message);
    }

    public static RuleResult review(String code, String message) {
        return new RuleResult(code, RuleOutcome.REVIEW, message);
    }

    public static RuleResult notApplicable(String code) {
        return new RuleResult(code, RuleOutcome.NOT_APPLICABLE, null);
    }
}
