package com.healthclaim.document.chunker;

import com.healthclaim.config.IngestionProperties;
import com.healthclaim.document.parser.ExtractedDocument;
import com.healthclaim.document.parser.PageText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    @Test
    void splitsIntoMultipleContextPreservingChunks() {
        IngestionProperties props = new IngestionProperties(120, 20, 40);
        DocumentChunker chunker = new DocumentChunker(props);

        String page = String.join("\n\n",
                "SECTION 4 COVERAGE",
                "The policy covers inpatient hospitalization expenses for medically necessary procedures.",
                "Daycare procedures and ambulance charges are reimbursed up to the sub-limits stated here.",
                "Pre and post hospitalization expenses are covered for sixty and ninety days respectively.");

        List<Chunk> chunks = chunker.chunk(new ExtractedDocument(1, List.of(new PageText(1, page))));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.pageNumber()).isEqualTo(1));
        assertThat(chunks).anySatisfy(c -> assertThat(c.heading()).contains("SECTION 4"));
        assertThat(chunks.stream().map(Chunk::text).reduce("", (a, b) -> a + " " + b))
                .contains("inpatient hospitalization");
    }

    @Test
    void emitsSingleChunkForShortDocument() {
        IngestionProperties props = new IngestionProperties(1000, 150, 200);
        DocumentChunker chunker = new DocumentChunker(props);

        List<Chunk> chunks = chunker.chunk(new ExtractedDocument(1,
                List.of(new PageText(1, "Short clause about coverage limits."))));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("coverage limits");
    }
}
