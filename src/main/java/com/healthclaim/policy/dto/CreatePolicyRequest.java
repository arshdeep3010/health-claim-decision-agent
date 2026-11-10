package com.healthclaim.policy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.healthclaim.policy.domain.PolicyType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to create a policy. Rule fields (waitingPeriods, exclusions,
 * coverageRules) are optional JSON-text strings; policy documents remain the
 * source of truth for natural-language clauses.
 */
public record CreatePolicyRequest(
        @NotBlank @Size(max = 64) String policyNumber,
        @NotNull PolicyType policyType,
        @NotNull LocalDate effectiveDate,
        @NotNull LocalDate expiryDate,
        @NotNull @Positive BigDecimal coverageLimit,
        @PositiveOrZero BigDecimal deductible,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal copayPercentage,
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
        @Size(max = 64) String holderReference,
        @Size(max = 255) String holderName,
        String waitingPeriods,
        String exclusions,
        String coverageRules
) {

    @JsonIgnore
    @AssertTrue(message = "expiryDate must be after effectiveDate")
    public boolean isDateRangeValid() {
        return effectiveDate == null || expiryDate == null || expiryDate.isAfter(effectiveDate);
    }
}
