package com.healthclaim.claim.dto;

/**
 * API representation of one stored claim fact.
 */
public record ClaimFactResponse(
        String key,
        String value,
        String valueType,
        String status,
        String source,
        Double confidence
) {
}
