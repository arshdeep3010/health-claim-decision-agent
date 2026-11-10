package com.healthclaim.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Patient details supplied with a claim. The patient is created on first use
 * (keyed by {@code reference}) or reused if already present. Synthetic data only.
 */
public record PatientRequest(
        @NotBlank @Size(max = 64) String reference,
        @NotBlank @Size(max = 255) String displayName,
        @Past LocalDate dateOfBirth,
        @Size(max = 16) String gender
) {
}
