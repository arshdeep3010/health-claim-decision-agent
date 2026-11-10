package com.healthclaim.synthetic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Jackson-bound view of the synthetic dataset (classpath: synthetic/dataset.json).
 * SYNTHETIC DATA ONLY.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyntheticDataset(List<SyntheticPolicy> policies, List<SyntheticClaim> claims) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyntheticPolicy(
            String policyNumber,
            String policyType,
            String status,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            BigDecimal coverageLimit,
            BigDecimal deductible,
            BigDecimal copayPercentage,
            String currency,
            List<String> exclusions,
            Map<String, Integer> waitingPeriods
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyntheticClaim(
            String claimNumber,
            String policyNumber,
            String patientReference,
            String patientName,
            String provider,
            String diagnosis,
            String procedureName,
            String treatment,
            LocalDate hospitalizationStart,
            LocalDate hospitalizationEnd,
            BigDecimal claimAmount,
            String currency,
            Map<String, FactValue> facts,
            String scenario,
            String expectedDecision
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FactValue(String value, String status) {
    }
}
