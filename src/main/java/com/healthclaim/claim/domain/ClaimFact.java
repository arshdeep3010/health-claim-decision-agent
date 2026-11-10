package com.healthclaim.claim.domain;

import com.healthclaim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single structured fact about a claim (e.g. diagnosis, hospitalizationDays,
 * preExistingCondition), with a known/unknown/conflicting status. Confidence is
 * only populated when the model actually provides it; otherwise it stays null.
 */
@Entity
@Table(name = "claim_fact")
public class ClaimFact extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(name = "fact_key", nullable = false, length = 64)
    private String factKey;

    @Column(name = "fact_value", columnDefinition = "text")
    private String factValue;

    /** STRING | NUMBER | BOOLEAN | DATE (enforced by a DB check constraint). */
    @Column(name = "value_type", length = 16)
    private String valueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FactStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16)
    private FactSource source;

    @Column(name = "confidence")
    private Double confidence;

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    public String getFactKey() {
        return factKey;
    }

    public void setFactKey(String factKey) {
        this.factKey = factKey;
    }

    public String getFactValue() {
        return factValue;
    }

    public void setFactValue(String factValue) {
        this.factValue = factValue;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public FactStatus getStatus() {
        return status;
    }

    public void setStatus(FactStatus status) {
        this.status = status;
    }

    public FactSource getSource() {
        return source;
    }

    public void setSource(FactSource source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
