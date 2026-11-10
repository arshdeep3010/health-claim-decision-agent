package com.healthclaim.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end RAG test on the default (deterministic, offline) embedding provider:
 * ingest a 2-page policy PDF, then verify semantic retrieval ranks the correct
 * page first and returns traceable, scored evidence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class RagRetrievalIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void retrievesRankedTraceableEvidence() throws Exception {
        String policyId = createPolicy("POL-RAG");

        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.pdf", "application/pdf", twoPagePolicyPdf());
        mvc.perform(multipart("/api/v1/policies/{id}/documents", policyId).file(file))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/{id}/ingest", policyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunksCreated", greaterThan(1)));

        // Query exclusion terms -> page 2 (exclusions) should rank first.
        String resp = mvc.perform(get("/api/v1/policies/{id}/search", policyId)
                        .param("query", "cosmetic surgery experimental treatment excluded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", notNullValue()))
                .andExpect(jsonPath("$[0].pageNumber", is(2)))
                .andExpect(jsonPath("$[0].policyId", is(policyId)))
                .andExpect(jsonPath("$[0].chunkId", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        // Assert similarity in Java (JsonPath parses decimals as BigDecimal, which
        // breaks Hamcrest's Double greaterThan matcher).
        double similarity = om.readTree(resp).get(0).get("similarity").asDouble();
        assertThat(similarity).isGreaterThan(0.0);

        // Query coverage terms -> page 1 (coverage) should rank first.
        mvc.perform(get("/api/v1/policies/{id}/search", policyId)
                        .param("query", "inpatient hospitalization ambulance charges covered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageNumber", is(1)));
    }

    @Test
    void blankQueryIsRejected() throws Exception {
        String policyId = createPolicy("POL-RAG-BLANK");
        mvc.perform(get("/api/v1/policies/{id}/search", policyId).param("query", " "))
                .andExpect(status().isBadRequest());
    }

    private String createPolicy(String policyNumber) throws Exception {
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
        return om.readTree(resp).get("policyId").asText();
    }

    private static byte[] twoPagePolicyPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            addPage(document, List.of(
                    "SECTION 4 COVERAGE",
                    "",
                    "The policy covers inpatient hospitalization for medically necessary procedures.",
                    "Daycare procedures and ambulance charges are reimbursed up to stated sub-limits.",
                    "Pre and post hospitalization expenses are also covered."));
            addPage(document, List.of(
                    "SECTION 5 EXCLUSIONS",
                    "",
                    "Cosmetic surgery and experimental treatments are excluded from coverage.",
                    "Self-inflicted injuries and cosmetic dental procedures are not reimbursed."));
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
