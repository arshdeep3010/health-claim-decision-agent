package com.healthclaim.decision.dto;

import java.util.UUID;

public record EvidenceResponse(
        UUID chunkId,
        UUID documentId,
        UUID policyId,
        Integer pageNumber,
        String section,
        String heading,
        Double similarity,
        Integer rank,
        String snippet
) {
}
