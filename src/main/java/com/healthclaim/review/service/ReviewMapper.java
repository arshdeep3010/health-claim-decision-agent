package com.healthclaim.review.service;

import com.healthclaim.review.domain.HumanReview;
import com.healthclaim.review.dto.ReviewResponse;

public final class ReviewMapper {

    private ReviewMapper() {
    }

    public static ReviewResponse toResponse(HumanReview r) {
        return new ReviewResponse(
                r.getId(),
                r.getClaim() != null ? r.getClaim().getId() : null,
                r.getReviewerId(),
                r.getReviewDecision() != null ? r.getReviewDecision().name() : null,
                r.getReason(),
                r.getPreviousAiDecision() != null ? r.getPreviousAiDecision().name() : null,
                r.getFinalDecision() != null ? r.getFinalDecision().name() : null,
                r.getAgreedWithAi(),
                r.getReviewedAt(),
                r.getCreatedAt());
    }
}
