package com.healthclaim.claim.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API representation of a claim. Entities are never returned directly.
 */
public record ClaimResponse(
        UUID claimId,
        String claimNumber,
        String policyNumber,
        String patientReference,
        String provider,
        String diagnosis,
        String procedureName,
        String treatment,
        LocalDate hospitalizationStart,
        LocalDate hospitalizationEnd,
        BigDecimal claimAmount,
        String currency,
        String status,
        Instant submittedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
