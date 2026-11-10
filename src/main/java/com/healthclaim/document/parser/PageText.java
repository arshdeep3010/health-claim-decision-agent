package com.healthclaim.document.parser;

/**
 * Extracted text for a single page of a document.
 */
public record PageText(int pageNumber, String text) {
}
