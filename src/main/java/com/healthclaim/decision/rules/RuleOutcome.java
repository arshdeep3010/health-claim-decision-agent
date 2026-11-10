package com.healthclaim.decision.rules;

/**
 * What a single deterministic rule concludes.
 */
public enum RuleOutcome {
    /** The rule applied and found no objection. */
    ALLOW,
    /** Hard constraint violated — the claim must be rejected (Java-owned). */
    REJECT,
    /** The claim must be routed to a human reviewer. */
    REVIEW,
    /** The rule did not apply to this claim. */
    NOT_APPLICABLE
}
