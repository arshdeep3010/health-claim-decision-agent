package com.healthclaim.document.parser;

import java.util.List;

/**
 * Result of extracting text from a document: total page count and per-page text.
 */
public record ExtractedDocument(int pageCount, List<PageText> pages) {
}
