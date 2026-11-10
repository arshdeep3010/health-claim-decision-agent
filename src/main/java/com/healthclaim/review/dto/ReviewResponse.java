package com.healthclaim.review.dto;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID reviewId,
        UUID claimId,
        String reviewerId,
        String reviewDecision,
        String reason,
        String previousAiDecision,
        String finalDecision,
        Boolean agreedWithAi,
        Instant reviewedAt,
        Instant createdAt
) {
}
