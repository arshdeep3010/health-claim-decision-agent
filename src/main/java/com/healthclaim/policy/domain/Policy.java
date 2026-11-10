package com.healthclaim.policy.domain;

import com.healthclaim.common.domain.BaseEntity;
import com.healthclaim.patient.domain.Patient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An insurance policy. Structured rule fields (waiting periods, exclusions,
 * coverage rules) are stored as JSON text; the policy documents remain the
 * source of truth for natural-language clauses.
 */
@Entity
@Table(name = "policy")
public class Policy extends BaseEntity {

    @Column(name = "policy_number", nullable = false, unique = true, length = 64)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 32)
    private PolicyType policyType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holder_patient_id")
    private Patient holder;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "coverage_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal coverageLimit;

    @Column(name = "deductible", nullable = false, precision = 14, scale = 2)
    private BigDecimal deductible;

    @Column(name = "copay_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal copayPercentage;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PolicyStatus status;

    /** JSON text, e.g. {"PRE_EXISTING":48,"MATERNITY":24} (months). */
    @Column(name = "waiting_periods")
    private String waitingPeriods;

    /** JSON text array of excluded procedures/conditions. */
    @Column(name = "exclusions")
    private String exclusions;

    /** JSON text of additional structured coverage rules. */
    @Column(name = "coverage_rules")
    private String coverageRules;

    public String getPolicyNumber() {
        return policyNumber;
    }

    public void setPolicyNumber(String policyNumber) {
        this.policyNumber = policyNumber;
    }

    public PolicyType getPolicyType() {
        return policyType;
    }

    public void setPolicyType(PolicyType policyType) {
        this.policyType = policyType;
    }

    public Patient getHolder() {
        return holder;
    }

    public void setHolder(Patient holder) {
        this.holder = holder;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public BigDecimal getCoverageLimit() {
        return coverageLimit;
    }

    public void setCoverageLimit(BigDecimal coverageLimit) {
        this.coverageLimit = coverageLimit;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }

    public void setDeductible(BigDecimal deductible) {
        this.deductible = deductible;
    }

    public BigDecimal getCopayPercentage() {
        return copayPercentage;
    }

    public void setCopayPercentage(BigDecimal copayPercentage) {
        this.copayPercentage = copayPercentage;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyStatus status) {
        this.status = status;
    }

    public String getWaitingPeriods() {
        return waitingPeriods;
    }

    public void setWaitingPeriods(String waitingPeriods) {
        this.waitingPeriods = waitingPeriods;
    }

    public String getExclusions() {
        return exclusions;
    }

    public void setExclusions(String exclusions) {
        this.exclusions = exclusions;
    }

    public String getCoverageRules() {
        return coverageRules;
    }

    public void setCoverageRules(String coverageRules) {
        this.coverageRules = coverageRules;
    }
}
