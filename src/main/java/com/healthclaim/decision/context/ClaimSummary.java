package com.healthclaim.decision.context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Typed summary of the claim fields relevant to a decision.
 */
public record ClaimSummary(
        UUID claimId,
        String claimNumber,
        String provider,
        String diagnosis,
        String procedureName,
        String treatment,
        LocalDate hospitalizationStart,
        LocalDate hospitalizationEnd,
        BigDecimal claimAmount,
        String currency
) {
    /** Best available service date for date-based rules. */
    public LocalDate serviceDate() {
        return hospitalizationStart != null ? hospitalizationStart : LocalDate.now();
    }
}
