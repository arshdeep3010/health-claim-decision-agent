package com.healthclaim.decision.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of a claim decision. {@code confidence} is null unless the
 * AI supplied one; {@code explanation} is populated in a later phase.
 */
public record DecisionResponse(
        UUID decisionId,
        UUID claimId,
        String decision,
        Double confidence,
        List<String> reasonCodes,
        List<String> missingInformation,
        List<String> uncertainties,
        List<EvidenceResponse> supportingEvidence,
        String summary,
        String modelProvider,
        String model,
        String promptVersion,
        Long latencyMs,
        String explanation,
        Instant createdAt
) {
}
