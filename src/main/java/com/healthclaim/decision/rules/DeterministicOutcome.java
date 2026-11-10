package com.healthclaim.decision.rules;

/**
 * Aggregate deterministic verdict over all rules, with strict precedence:
 * any REJECT wins; else any REVIEW wins; else ALLOW.
 */
public enum DeterministicOutcome {
    REJECT,
    REVIEW,
    ALLOW
}
