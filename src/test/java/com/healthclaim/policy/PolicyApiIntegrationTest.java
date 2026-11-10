package com.healthclaim.policy;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
public class PolicyApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void createGetAndListPolicy() throws Exception {
        String id = createPolicy("POL-API-1");

        mvc.perform(get("/api/v1/policies/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-API-1")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        mvc.perform(get("/api/v1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", notNullValue()))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)));
    }

    @Test
    void duplicatePolicyReturns409() throws Exception {
        createPolicy("POL-DUP");
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(policyBody("POL-DUP"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("POLICY_ALREADY_EXISTS")));
    }

    @Test
    void uploadAndIngestProducesChunks() throws Exception {
        String id = createPolicy("POL-ING");

        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.pdf", "application/pdf", samplePolicyPdf());

        mvc.perform(multipart("/api/v1/policies/{id}/documents", id).file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("UPLOADED")))
                .andExpect(jsonPath("$.sha256", notNullValue()));

        mvc.perform(post("/api/v1/policies/{id}/ingest", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed", is(1)))
                .andExpect(jsonPath("$.chunksCreated", greaterThan(0)));

        mvc.perform(get("/api/v1/policies/{id}/documents", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("INGESTED")))
                .andExpect(jsonPath("$[0].pageCount", greaterThanOrEqualTo(1)));
    }

    @Test
    void chunksEndpointListsIndexedWordingWithEmbeddingModel() throws Exception {
        String id = createPolicy("POL-CHUNKS");
        mvc.perform(get("/api/v1/policies/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(multipart("/api/v1/policies/{id}/documents", id)
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", samplePolicyPdf())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/{id}/ingest", id)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/policies/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].text", containsString("inpatient hospitalization")))
                .andExpect(jsonPath("$[0].embeddingModel", is("local-deterministic-v1")))
                .andExpect(jsonPath("$[0].embeddingDimensions", is(384)));
    }

    @Test
    void chunksForUnknownPolicyIs404() throws Exception {
        mvc.perform(get("/api/v1/policies/{id}/chunks", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reembedReprocessesAlreadyIngestedDocuments() throws Exception {
        String id = createPolicy("POL-REEMBED");
        mvc.perform(multipart("/api/v1/policies/{id}/documents", id)
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", samplePolicyPdf())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/{id}/ingest", id))
                .andExpect(jsonPath("$.documentsProcessed", is(1)));

        // A plain ingest leaves INGESTED documents alone...
        mvc.perform(post("/api/v1/policies/{id}/ingest", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed", is(0)));

        // ...reembed rebuilds them with the current embedding provider.
        mvc.perform(post("/api/v1/policies/{id}/ingest", id).param("reembed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed", is(1)))
                .andExpect(jsonPath("$.chunksCreated", greaterThan(0)));
    }

    @Test
    void rejectsNonPdfUpload() throws Exception {
        String id = createPolicy("POL-BAD");

        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "this is not a pdf".getBytes());

        mvc.perform(multipart("/api/v1/policies/{id}/documents", id).file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")));
    }

    @Test
    void validationErrorOnBadPolicy() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("policyNumber", "");
        bad.put("policyType", "INDIVIDUAL");
        bad.put("effectiveDate", "2020-01-01");
        bad.put("expiryDate", "2019-01-01"); // before effective -> cross-field failure
        bad.put("coverageLimit", -1);

        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    private String createPolicy(String policyNumber) throws Exception {
        String body = mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(policyBody(policyNumber))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("policyId").asText();
    }

    private Map<String, Object> policyBody(String policyNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyNumber", policyNumber);
        body.put("policyType", "INDIVIDUAL");
        body.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        body.put("expiryDate", LocalDate.now().plusYears(1).toString());
        body.put("coverageLimit", 500000);
        body.put("deductible", 5000);
        body.put("copayPercentage", 10);
        body.put("currency", "INR");
        body.put("holderReference", "HOLD-" + policyNumber);
        body.put("holderName", "Policy Holder");
        return body;
    }

    /** A one-page synthetic policy-wording PDF (shared with other integration tests). */
    public static byte[] samplePolicyPdf() throws Exception {
        List<String> lines = List.of(
                "SECTION 4 COVERAGE",
                "",
                "The policy covers inpatient hospitalization for medically necessary procedures.",
                "",
                "Daycare procedures and ambulance charges are reimbursed up to stated sub-limits.",
                "",
                "SECTION 5 EXCLUSIONS",
                "",
                "Cosmetic surgery and experimental treatments are excluded from coverage.");

        try (PDDocument document = new PDDocument()) {
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
