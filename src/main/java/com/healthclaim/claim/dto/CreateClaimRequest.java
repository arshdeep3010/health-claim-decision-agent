package com.healthclaim.claim.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for submitting a claim. The referenced policy must already exist;
 * the patient is created or reused by reference.
 */
public record CreateClaimRequest(
        @NotBlank @Size(max = 64) String policyNumber,
        @NotNull @Valid PatientRequest patient,
        @Size(max = 255) String provider,
        @Size(max = 512) String diagnosis,
        @Size(max = 512) String procedureName,
        String treatment,
        LocalDate hospitalizationStart,
        LocalDate hospitalizationEnd,
        @NotNull @Positive BigDecimal claimAmount,
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency
) {

    @JsonIgnore
    @AssertTrue(message = "hospitalizationEnd must be on or after hospitalizationStart")
    public boolean isHospitalizationRangeValid() {
        return hospitalizationStart == null
                || hospitalizationEnd == null
                || !hospitalizationEnd.isBefore(hospitalizationStart);
    }
}
