package com.healthclaim.policy.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyDocumentResponse(
        UUID documentId,
        UUID policyId,
        String originalFilename,
        String contentType,
        Long fileSizeBytes,
        String sha256,
        String status,
        Integer pageCount,
        Instant createdAt
) {
}
