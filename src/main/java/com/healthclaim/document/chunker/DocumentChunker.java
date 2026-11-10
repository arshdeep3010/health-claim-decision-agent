package com.healthclaim.document.chunker;

import com.healthclaim.config.IngestionProperties;
import com.healthclaim.document.parser.ExtractedDocument;
import com.healthclaim.document.parser.PageText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Context-preserving chunker. It works paragraph-by-paragraph within each page
 * (so page numbers stay meaningful), packs whole paragraphs up to a target size,
 * carries a small paragraph-level overlap for continuity, hard-splits unusually
 * long paragraphs, and tracks the most recent heading so each chunk records the
 * section it belongs to. This avoids blindly splitting every N characters.
 */
@Component
public class DocumentChunker {

    private final IngestionProperties props;

    public DocumentChunker(IngestionProperties props) {
        this.props = props;
    }

    public List<Chunk> chunk(ExtractedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        for (PageText page : document.pages()) {
            chunkPage(page, chunks);
        }
        return chunks;
    }

    private void chunkPage(PageText page, List<Chunk> out) {
        String[] rawParagraphs = page.text().split("\\n\\s*\\n");
        String heading = null;
        List<String> current = new ArrayList<>();
        int currentLen = 0;

        for (String raw : rawParagraphs) {
            String paragraph = raw.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (isHeading(paragraph)) {
                heading = firstLine(paragraph);
            }

            // Hard-split a single oversized paragraph.
            if (paragraph.length() > props.chunkSize()) {
                if (!current.isEmpty()) {
                    out.add(makeChunk(current, page.pageNumber(), heading));
                    current = new ArrayList<>();
                    currentLen = 0;
                }
                int step = Math.max(1, props.chunkSize() - props.chunkOverlap());
                for (int start = 0; start < paragraph.length(); start += step) {
                    int end = Math.min(paragraph.length(), start + props.chunkSize());
                    String piece = paragraph.substring(start, end).strip();
                    if (!piece.isEmpty()) {
                        out.add(new Chunk(page.pageNumber(), heading, heading, piece));
                    }
                    if (end == paragraph.length()) {
                        break;
                    }
                }
                continue;
            }

            // Emit the accumulated chunk if adding this paragraph would overflow.
            if (currentLen > 0
                    && currentLen + paragraph.length() > props.chunkSize()
                    && currentLen >= props.minChunkSize()) {
                out.add(makeChunk(current, page.pageNumber(), heading));
                String overlapParagraph = current.get(current.size() - 1);
                current = new ArrayList<>();
                currentLen = 0;
                if (props.chunkOverlap() > 0 && overlapParagraph.length() <= props.chunkOverlap() * 2) {
                    current.add(overlapParagraph);
                    currentLen = overlapParagraph.length();
                }
            }

            current.add(paragraph);
            currentLen += paragraph.length() + 2;
        }

        if (!current.isEmpty()) {
            out.add(makeChunk(current, page.pageNumber(), heading));
        }
    }

    private Chunk makeChunk(List<String> paragraphs, int pageNumber, String heading) {
        String text = String.join("\n\n", paragraphs).strip();
        return new Chunk(pageNumber, heading, heading, text);
    }

    private boolean isHeading(String paragraph) {
        String line = firstLine(paragraph);
        if (line.isEmpty() || line.length() > 80) {
            return false;
        }
        if (line.matches("(?i)^(section|clause|article|part|schedule)\\b.*")) {
            return true;
        }
        if (line.matches("^\\d+(\\.\\d+)*\\.?\\s+\\S.*")) {
            return true;
        }
        String letters = line.replaceAll("[^A-Za-z]", "");
        if (!letters.isEmpty() && line.length() <= 60) {
            long upper = letters.chars().filter(Character::isUpperCase).count();
            return (double) upper / letters.length() > 0.7;
        }
        return false;
    }

    private String firstLine(String paragraph) {
        int newline = paragraph.indexOf('\n');
        return (newline < 0 ? paragraph : paragraph.substring(0, newline)).strip();
    }
}
