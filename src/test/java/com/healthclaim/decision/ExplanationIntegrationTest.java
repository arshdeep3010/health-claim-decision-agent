package com.healthclaim.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class ExplanationIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @MockitoBean
    private ChatLlmProvider chatLlmProvider; // isConfigured() defaults to false -> template path

    @Test
    void templateExplanationIsGeneratedOfflineAndDoesNotChangeDecision() throws Exception {
        String claimId = createAndAnalyze("POL-EXPL1"); // -> HUMAN_REVIEW (missing facts)

        mvc.perform(post("/api/v1/claims/{id}/explain", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.explanation", containsString("Decision: HUMAN_REVIEW")));

        // Decision itself is unchanged; explanation is now attached.
        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.explanation", notNullValue()));
    }

    @Test
    void llmExplanationIsUsedWhenConfiguredAndDoesNotChangeDecision() throws Exception {
        when(chatLlmProvider.isConfigured()).thenReturn(true);
        when(chatLlmProvider.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("The claim needs human review due to missing information.",
                        "mock-model", 10, 20, "stop", 12, 0));

        String claimId = createAndAnalyze("POL-EXPL2");

        mvc.perform(post("/api/v1/claims/{id}/explain", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation", is("The claim needs human review due to missing information.")));

        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW"))); // unchanged by explanation
    }

    @Test
    void explainBeforeAnalyzeReturns404() throws Exception {
        String claimId = createClaim("POL-EXPL3");
        mvc.perform(post("/api/v1/claims/{id}/explain", claimId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("DECISION_NOT_FOUND")));
    }

    private String createAndAnalyze(String policyNumber) throws Exception {
        String claimId = createClaim(policyNumber);
        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId)).andExpect(status().isOk());
        return claimId;
    }

    private String createClaim(String policyNumber) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyNumber", policyNumber);
        policy.put("policyType", "INDIVIDUAL");
        policy.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        policy.put("expiryDate", LocalDate.now().plusYears(1).toString());
        policy.put("coverageLimit", 500000);
        policy.put("currency", "INR");
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(policy))).andExpect(status().isCreated());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policyNumber", policyNumber);
        claim.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        claim.put("diagnosis", "Gallstones");
        claim.put("procedureName", "Laparoscopic Cholecystectomy");
        claim.put("claimAmount", 145000);
        claim.put("currency", "INR");
        String resp = mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claim)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("claimId").asText();
    }
}
