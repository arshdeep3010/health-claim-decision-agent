package com.healthclaim.audit.domain;

import com.healthclaim.common.domain.ImmutableEntity;
import com.healthclaim.decision.domain.DecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Append-only audit record. {@code detail} holds structured JSON text and must
 * never contain secrets or raw sensitive data.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog extends ImmutableEntity {

    @Column(name = "claim_id")
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuditEventType eventType;

    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_decision", length = 16)
    private DecisionType finalDecision;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "human_override")
    private Boolean humanOverride;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    public UUID getClaimId() {
        return claimId;
    }

    public void setClaimId(UUID claimId) {
        this.claimId = claimId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public void setEventType(AuditEventType eventType) {
        this.eventType = eventType;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public DecisionType getFinalDecision() {
        return finalDecision;
    }

    public void setFinalDecision(DecisionType finalDecision) {
        this.finalDecision = finalDecision;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Boolean getHumanOverride() {
        return humanOverride;
    }

    public void setHumanOverride(Boolean humanOverride) {
        this.humanOverride = humanOverride;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
