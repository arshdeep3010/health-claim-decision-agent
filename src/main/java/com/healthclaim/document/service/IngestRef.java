package com.healthclaim.document.service;

import java.util.UUID;

/**
 * Minimal reference data needed to ingest a document, loaded in a short read
 * transaction so parsing can happen outside any transaction.
 */
public record IngestRef(String storageKey, UUID policyId) {
}
