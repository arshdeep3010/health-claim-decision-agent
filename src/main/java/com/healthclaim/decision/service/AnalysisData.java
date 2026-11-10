package com.healthclaim.decision.service;

import com.healthclaim.decision.context.ClaimFactsView;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.PolicyFacts;

import java.util.UUID;

/**
 * Immutable snapshot of the DB state needed to analyze a claim, loaded in a single
 * short read transaction.
 */
public record AnalysisData(
        ClaimSummary claim,
        PolicyFacts policy,
        ClaimFactsView facts,
        UUID policyId
) {
}
