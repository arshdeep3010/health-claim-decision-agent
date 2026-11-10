package com.healthclaim.policy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PolicyResponse(
        UUID policyId,
        String policyNumber,
        String policyType,
        String holderReference,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        BigDecimal coverageLimit,
        BigDecimal deductible,
        BigDecimal copayPercentage,
        String currency,
        String status,
        String waitingPeriods,
        String exclusions,
        String coverageRules,
        Instant createdAt,
        Instant updatedAt
) {
}
