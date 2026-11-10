package com.healthclaim.rag.evidence;

import java.util.UUID;

/**
 * A retrieved piece of policy evidence with its similarity score. Fully traceable
 * back to the source chunk, document, page and section.
 */
public record EvidenceResult(
        UUID chunkId,
        UUID documentId,
        UUID policyId,
        Integer pageNumber,
        String section,
        String heading,
        String snippet,
        double similarity
) {
}
