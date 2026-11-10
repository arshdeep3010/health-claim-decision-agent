package com.healthclaim.evaluation;

import com.healthclaim.decision.domain.DecisionType;

/**
 * One claim's evaluation outcome: the expected (labeled) decision vs. what an arm
 * predicted.
 */
public record EvaluationResult(
        String claimNumber,
        String scenario,
        DecisionType expected,
        DecisionType predicted
) {
    public boolean correct() {
        return expected == predicted;
    }
}
