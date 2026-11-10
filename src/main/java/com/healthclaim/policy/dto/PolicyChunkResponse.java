package com.healthclaim.policy.dto;

import java.util.UUID;

/**
 * One indexed piece of policy wording, as stored for RAG: its text, where it came
 * from, and which embedding model produced its vector. The vector itself is not
 * returned, only its dimension.
 */
public record PolicyChunkResponse(
        UUID chunkId,
        UUID documentId,
        int chunkIndex,
        Integer pageNumber,
        String section,
        String heading,
        String text,
        Integer tokenCount,
        String embeddingModel,
        Integer embeddingDimensions
) {
}
