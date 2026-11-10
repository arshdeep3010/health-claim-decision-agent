package com.healthclaim.decision.service;

import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.rag.evidence.EvidenceResult;

import java.util.List;

/**
 * The orchestrator's final, Java-owned decision for a claim, ready to persist and
 * return. {@code confidence} is non-null only when the AI supplied one.
 */
public record DecisionOutcome(
        DecisionType decision,
        Double confidence,
        List<String> reasonCodes,
        List<String> missingInformation,
        List<String> uncertainties,
        String summary,
        String modelProvider,
        String model,
        String promptVersion,
        long latencyMs,
        List<EvidenceResult> evidence
) {
}
