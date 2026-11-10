package com.healthclaim.audit.domain;

/**
 * Types of auditable events across the claim lifecycle. Stored as text (no DB
 * check constraint) so new event types can be added without a migration.
 */
public enum AuditEventType {
    CLAIM_SUBMITTED,
    CLAIM_UPDATED,
    ANALYSIS_STARTED,
    RAG_RETRIEVAL,
    AI_EXTRACTION,
    RULES_EVALUATED,
    AI_DECISION,
    DECISION_PERSISTED,
    EXPLANATION_GENERATED,
    HUMAN_REVIEW_SUBMITTED,
    HUMAN_OVERRIDE,
    AI_PROVIDER_FAILURE,
    RAG_FAILURE,
    ERROR
}
