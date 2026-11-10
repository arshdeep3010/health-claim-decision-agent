package com.healthclaim.document.chunker;

/**
 * A retrievable chunk produced by the chunker, with best-effort section/heading
 * context and the source page number.
 */
public record Chunk(Integer pageNumber, String section, String heading, String text) {
}
