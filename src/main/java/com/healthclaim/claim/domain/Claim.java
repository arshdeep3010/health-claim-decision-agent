package com.healthclaim.claim.domain;

import com.healthclaim.common.domain.BaseEntity;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.policy.domain.Policy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A synthetic health-insurance claim.
 */
@Entity
@Table(name = "claim")
public class Claim extends BaseEntity {

    @Column(name = "claim_number", nullable = false, unique = true, length = 64)
    private String claimNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "provider")
    private String provider;

    @Column(name = "diagnosis", length = 512)
    private String diagnosis;

    @Column(name = "procedure_name", length = 512)
    private String procedureName;

    @Column(name = "treatment", columnDefinition = "text")
    private String treatment;

    @Column(name = "hospitalization_start")
    private LocalDate hospitalizationStart;

    @Column(name = "hospitalization_end")
    private LocalDate hospitalizationEnd;

    @Column(name = "claim_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal claimAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClaimStatus status;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    public String getClaimNumber() {
        return claimNumber;
    }

    public void setClaimNumber(String claimNumber) {
        this.claimNumber = claimNumber;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }

    public Patient getPatient() {
        return patient;
    }

    public void setPatient(Patient patient) {
        this.patient = patient;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(String diagnosis) {
        this.diagnosis = diagnosis;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getTreatment() {
        return treatment;
    }

    public void setTreatment(String treatment) {
        this.treatment = treatment;
    }

    public LocalDate getHospitalizationStart() {
        return hospitalizationStart;
    }

    public void setHospitalizationStart(LocalDate hospitalizationStart) {
        this.hospitalizationStart = hospitalizationStart;
    }

    public LocalDate getHospitalizationEnd() {
        return hospitalizationEnd;
    }

    public void setHospitalizationEnd(LocalDate hospitalizationEnd) {
        this.hospitalizationEnd = hospitalizationEnd;
    }

    public BigDecimal getClaimAmount() {
        return claimAmount;
    }

    public void setClaimAmount(BigDecimal claimAmount) {
        this.claimAmount = claimAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }
}
