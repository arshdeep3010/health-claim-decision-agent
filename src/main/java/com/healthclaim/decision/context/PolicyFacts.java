package com.healthclaim.decision.context;

import com.healthclaim.policy.domain.PolicyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Typed, rule-friendly view of a policy. Natural-language clauses stay in the
 * policy documents (RAG); this captures the structured fields rules need.
 */
public record PolicyFacts(
        String policyNumber,
        PolicyStatus status,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        BigDecimal coverageLimit,
        BigDecimal deductible,
        BigDecimal copayPercentage,
        String currency,
        List<String> exclusions,
        Map<String, Integer> waitingPeriodMonths
) {
}
