package com.healthclaim.review.domain;

/**
 * The action a human reviewer can take on a claim.
 */
public enum ReviewDecision {
    APPROVE,
    REJECT,
    REQUEST_INFORMATION
}
