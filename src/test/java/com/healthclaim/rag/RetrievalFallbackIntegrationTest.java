package com.healthclaim.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.rag.embedding.EmbeddingException;
import com.healthclaim.rag.embedding.EmbeddingProvider;
import com.healthclaim.rag.retrieval.RetrievalService;
import com.healthclaim.rag.retrieval.RetrievalService.Method;
import com.healthclaim.rag.retrieval.RetrievalService.Retrieval;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retrieval keeps working when the embedding API is down: query vectors are cached,
 * and without a cached vector it falls back to keyword search over the same chunks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class RetrievalFallbackIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private RetrievalService retrievalService;
    @MockitoSpyBean
    private EmbeddingProvider embeddingProvider;

    @AfterEach
    void restoreProvider() {
        reset(embeddingProvider);
    }

    @Test
    void reusesCachedQueryVectorWhenEmbeddingApiIsDown() throws Exception {
        UUID policyId = ingestedPolicy("POL-RAG-CACHE");
        String query = "Gallbladder stones laparoscopic cholecystectomy cache";

        Retrieval first = retrievalService.search(policyId, query, 3);
        assertThat(first.method()).isEqualTo(Method.VECTOR);
        assertThat(first.evidence()).isNotEmpty();

        doThrow(new EmbeddingException("Embedding provider returned HTTP 503: Service is overloaded"))
                .when(embeddingProvider).embedQuery(anyString());
        Retrieval second = retrievalService.search(policyId, query, 3);

        assertThat(second.method()).isEqualTo(Method.CACHED_VECTOR);
        assertThat(second.evidence()).extracting(e -> e.chunkId())
                .containsExactlyElementsOf(first.evidence().stream().map(e -> e.chunkId()).toList());
        verify(embeddingProvider, times(1)).embedQuery(anyString());   // the second search never called the API
    }

    @Test
    void fallsBackToKeywordSearchWhenEmbeddingApiIsDown() throws Exception {
        UUID policyId = ingestedPolicy("POL-RAG-KEYWORD");
        doThrow(new EmbeddingException("Embedding provider returned HTTP 503: Service is overloaded"))
                .when(embeddingProvider).embedQuery(anyString());

        Retrieval result = retrievalService.search(policyId, "Cosmetic rhinoplasty surgery", 3);

        assertThat(result.method()).isEqualTo(Method.KEYWORD);
        assertThat(result.evidence()).isNotEmpty();
        assertThat(result.evidence().get(0).pageNumber()).isEqualTo(2);         // the exclusions page
        assertThat(result.evidence().get(0).similarity()).isBetween(0.0, 1.0);
    }

    @Test
    void stillFailsWhenNothingMatchesEitherWay() throws Exception {
        UUID policyId = ingestedPolicy("POL-RAG-NOMATCH");
        doThrow(new EmbeddingException("Embedding provider returned HTTP 503: Service is overloaded"))
                .when(embeddingProvider).embedQuery(anyString());

        assertThatThrownBy(() -> retrievalService.search(policyId, "zzzqx wwwvq", 3))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("503");
    }

    private UUID ingestedPolicy(String policyNumber) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyNumber", policyNumber);
        body.put("policyType", "INDIVIDUAL");
        body.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        body.put("expiryDate", LocalDate.now().plusYears(1).toString());
        body.put("coverageLimit", 500000);
        body.put("currency", "INR");
        String resp = mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String policyId = om.readTree(resp).get("policyId").asText();

        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", twoPagePolicyPdf());
        mvc.perform(multipart("/api/v1/policies/{id}/documents", policyId).file(file))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/{id}/ingest", policyId)).andExpect(status().isOk());
        return UUID.fromString(policyId);
    }

    private static byte[] twoPagePolicyPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            addPage(document, List.of(
                    "SECTION 3 COVERED TREATMENT",
                    "",
                    "Laparoscopic cholecystectomy: removal of the gallbladder for gallstones.",
                    "Appendectomy: surgical removal of the appendix."));
            addPage(document, List.of(
                    "SECTION 4 EXCLUSIONS",
                    "",
                    "Cosmetic surgery, including rhinoplasty and liposuction, is excluded.",
                    "Experimental treatments are not reimbursed."));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void addPage(PDDocument document, List<String> lines) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.setLeading(16);
            cs.newLineAtOffset(50, 760);
            for (String line : lines) {
                cs.showText(line);
                cs.newLine();
            }
            cs.endText();
        }
    }
}
