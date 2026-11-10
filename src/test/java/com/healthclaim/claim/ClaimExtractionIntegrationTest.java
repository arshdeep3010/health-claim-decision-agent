package com.healthclaim.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class ClaimExtractionIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private ChatLlmProvider chatLlmProvider;

    private ChatResponse canned(String content) {
        return new ChatResponse(content, "mock-model", 20, 10, "stop", 12, 0);
    }

    @Test
    void extractPersistsFactsWithStatuses() throws Exception {
        when(chatLlmProvider.chat(any(ChatRequest.class))).thenReturn(canned("""
                {"facts":[
                 {"key":"diagnosis","value":"Gallstones","status":"KNOWN","confidence":0.95},
                 {"key":"procedure","value":"Laparoscopic Cholecystectomy","status":"KNOWN","confidence":null},
                 {"key":"hospitalizationDays","value":"3","status":"KNOWN","confidence":null},
                 {"key":"claimAmount","value":"145000","status":"KNOWN","confidence":null},
                 {"key":"preExistingCondition","value":null,"status":"UNKNOWN","confidence":null}
                ]}"""));

        String claimId = createClaim("POL-EX1", new BigDecimal("145000.00"));

        mvc.perform(post("/api/v1/claims/{id}/extract", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.facts", hasItem(Map.of(
                        "key", "diagnosis", "value", "Gallstones", "valueType", "STRING",
                        "status", "KNOWN", "source", "EXTRACTED", "confidence", 0.95))));

        // Persisted and retrievable
        mvc.perform(get("/api/v1/claims/{id}/facts", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='diagnosis')].status", hasItem("KNOWN")))
                .andExpect(jsonPath("$[?(@.key=='preExistingCondition')].status", hasItem("UNKNOWN")));

        // Claim advanced to PROCESSING
        mvc.perform(get("/api/v1/claims/{id}", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")));
    }

    @Test
    void defensivelyDowngradesUntrustworthyFacts() throws Exception {
        // KNOWN diagnosis with null value, and KNOWN claimAmount with a non-numeric value.
        when(chatLlmProvider.chat(any(ChatRequest.class))).thenReturn(canned("""
                {"facts":[
                 {"key":"diagnosis","value":null,"status":"KNOWN","confidence":null},
                 {"key":"claimAmount","value":"a lot","status":"KNOWN","confidence":5}
                ]}"""));

        String claimId = createClaim("POL-EX2", new BigDecimal("1000.00"));

        mvc.perform(post("/api/v1/claims/{id}/extract", claimId))
                .andExpect(status().isOk())
                // KNOWN-but-missing/unparseable are downgraded to UNKNOWN, out-of-range confidence dropped
                .andExpect(jsonPath("$.facts[?(@.key=='diagnosis')].status", hasItem("UNKNOWN")))
                .andExpect(jsonPath("$.facts[?(@.key=='claimAmount')].status", hasItem("UNKNOWN")));
    }

    @Test
    void malformedModelOutputReturns422() throws Exception {
        when(chatLlmProvider.chat(any(ChatRequest.class))).thenReturn(canned("not json at all {"));

        String claimId = createClaim("POL-EX3", new BigDecimal("1000.00"));

        mvc.perform(post("/api/v1/claims/{id}/extract", claimId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("CLAIM_EXTRACTION_ERROR")));
    }

    @Test
    void providerFailureReturns502() throws Exception {
        when(chatLlmProvider.chat(any(ChatRequest.class)))
                .thenThrow(new AiProviderException("provider unavailable"));

        String claimId = createClaim("POL-EX4", new BigDecimal("1000.00"));

        mvc.perform(post("/api/v1/claims/{id}/extract", claimId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", is("AI_PROVIDER_ERROR")));
    }

    // --- helpers ---

    private String createClaim(String policyNumber, BigDecimal amount) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyNumber", policyNumber);
        policy.put("policyType", "INDIVIDUAL");
        policy.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        policy.put("expiryDate", LocalDate.now().plusYears(1).toString());
        policy.put("coverageLimit", 500000);
        policy.put("currency", "INR");
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(policy)))
                .andExpect(status().isCreated());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policyNumber", policyNumber);
        claim.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        claim.put("provider", "Synthetic Hospital");
        claim.put("diagnosis", "Gallstones");
        claim.put("procedureName", "Laparoscopic Cholecystectomy");
        claim.put("treatment", "3-day inpatient hospitalization for gallstone removal.");
        claim.put("hospitalizationStart", LocalDate.now().minusDays(5).toString());
        claim.put("hospitalizationEnd", LocalDate.now().minusDays(2).toString());
        claim.put("claimAmount", amount);
        claim.put("currency", "INR");

        String resp = mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claim)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("claimId").asText();
    }
}
