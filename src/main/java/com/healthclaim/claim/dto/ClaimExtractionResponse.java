package com.healthclaim.claim.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of running fact extraction on a claim.
 */
public record ClaimExtractionResponse(
        UUID claimId,
        String status,
        List<ClaimFactResponse> facts
) {
}
