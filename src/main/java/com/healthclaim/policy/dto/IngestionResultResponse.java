package com.healthclaim.policy.dto;

import java.util.UUID;

public record IngestionResultResponse(
        UUID policyId,
        int documentsProcessed,
        int chunksCreated
) {
}
