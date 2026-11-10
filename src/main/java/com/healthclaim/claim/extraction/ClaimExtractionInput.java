package com.healthclaim.claim.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The controlled slice of claim information sent to the LLM for extraction.
 * Deliberately excludes identifiers, patient PII, and any DB internals.
 */
public record ClaimExtractionInput(
        String provider,
        String diagnosis,
        String procedureName,
        String treatment,
        LocalDate hospitalizationStart,
        LocalDate hospitalizationEnd,
        BigDecimal claimAmount,
        String currency
) {
}
