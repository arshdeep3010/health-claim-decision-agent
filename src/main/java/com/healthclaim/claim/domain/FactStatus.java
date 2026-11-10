package com.healthclaim.claim.domain;

/**
 * Whether an extracted claim fact is known, unknown, or contradicted by sources.
 */
public enum FactStatus {
    KNOWN,
    UNKNOWN,
    CONFLICTING
}
