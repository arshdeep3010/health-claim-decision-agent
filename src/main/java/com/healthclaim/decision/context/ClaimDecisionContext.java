package com.healthclaim.decision.context;

import com.healthclaim.decision.rules.DeterministicOutcome;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.rag.evidence.EvidenceResult;

import java.util.List;

/**
 * The strongly-typed object passed to the AI decision layer (§12). It bundles the
 * claim facts, policy facts, retrieved evidence, deterministic rule results, and
 * the derived uncertainties/missing information. Nothing else reaches the model.
 */
public record ClaimDecisionContext(
        ClaimSummary claim,
        PolicyFacts policy,
        List<FactEntry> extractedFacts,
        List<EvidenceResult> policyEvidence,
        List<RuleResult> ruleResults,
        DeterministicOutcome deterministicOutcome,
        List<String> uncertainties,
        List<String> missingInformation
) {
    public record FactEntry(String key, String value, String status) {
    }
}
