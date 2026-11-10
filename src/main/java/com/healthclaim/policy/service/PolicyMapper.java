package com.healthclaim.policy.service;

import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyDocument;
import com.healthclaim.policy.dto.PolicyDocumentResponse;
import com.healthclaim.policy.dto.PolicyResponse;

import java.util.UUID;

public final class PolicyMapper {

    private PolicyMapper() {
    }

    public static PolicyResponse toResponse(Policy p) {
        return new PolicyResponse(
                p.getId(),
                p.getPolicyNumber(),
                p.getPolicyType() != null ? p.getPolicyType().name() : null,
                p.getHolder() != null ? p.getHolder().getReference() : null,
                p.getEffectiveDate(),
                p.getExpiryDate(),
                p.getCoverageLimit(),
                p.getDeductible(),
                p.getCopayPercentage(),
                p.getCurrency(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getWaitingPeriods(),
                p.getExclusions(),
                p.getCoverageRules(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }

    public static PolicyDocumentResponse toDocumentResponse(PolicyDocument d, UUID policyId) {
        return new PolicyDocumentResponse(
                d.getId(),
                policyId,
                d.getOriginalFilename(),
                d.getContentType(),
                d.getFileSizeBytes(),
                d.getSha256(),
                d.getStatus() != null ? d.getStatus().name() : null,
                d.getPageCount(),
                d.getCreatedAt());
    }
}
