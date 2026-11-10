package com.healthclaim.review.dto;

import com.healthclaim.review.domain.ReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A human reviewer's verdict on a claim. The reviewer identity comes from the
 * authenticated principal, not the request body.
 */
public record SubmitReviewRequest(
        @NotNull ReviewDecision reviewDecision,
        @Size(max = 4000) String reason
) {
}
