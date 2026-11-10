package com.healthclaim.common.domain;

/**
 * Lifecycle status shared by policy documents and claim documents.
 */
public enum DocumentStatus {
    UPLOADED,
    INGESTING,
    INGESTED,
    FAILED
}
