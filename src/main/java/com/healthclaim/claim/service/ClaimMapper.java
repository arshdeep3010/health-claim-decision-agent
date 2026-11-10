package com.healthclaim.claim.service;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.dto.ClaimResponse;

/**
 * Maps claim entities to API DTOs. Must be called within an open transaction so
 * that lazy {@code policy}/{@code patient} associations can be read.
 */
public final class ClaimMapper {

    private ClaimMapper() {
    }

    public static ClaimResponse toResponse(Claim c) {
        return new ClaimResponse(
                c.getId(),
                c.getClaimNumber(),
                c.getPolicy() != null ? c.getPolicy().getPolicyNumber() : null,
                c.getPatient() != null ? c.getPatient().getReference() : null,
                c.getProvider(),
                c.getDiagnosis(),
                c.getProcedureName(),
                c.getTreatment(),
                c.getHospitalizationStart(),
                c.getHospitalizationEnd(),
                c.getClaimAmount(),
                c.getCurrency(),
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getSubmittedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
