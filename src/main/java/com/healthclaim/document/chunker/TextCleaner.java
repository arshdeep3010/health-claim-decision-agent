package com.healthclaim.document.chunker;

import org.springframework.stereotype.Component;

/**
 * Normalises raw extracted text: unifies line endings, strips control characters,
 * and collapses excessive whitespace while preserving paragraph breaks.
 */
@Component
public class TextCleaner {

    public String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        // Drop control chars except newline (\n) and tab (\t).
        text = text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", " ");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll(" *\\n *", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }
}
