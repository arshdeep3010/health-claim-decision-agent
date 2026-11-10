package com.healthclaim.decision.context;

import com.healthclaim.rag.evidence.EvidenceResult;

import java.util.List;

/**
 * Everything the rules consume: the claim, the typed policy facts, the extracted
 * claim facts, and the retrieved policy evidence. Rules never touch the database.
 */
public record DecisionInputs(
        ClaimSummary claim,
        PolicyFacts policy,
        ClaimFactsView facts,
        List<EvidenceResult> evidence
) {
}
