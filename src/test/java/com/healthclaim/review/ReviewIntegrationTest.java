package com.healthclaim.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(username = "rev-1", roles = {"REVIEWER", "ADMIN", "USER"})
class ReviewIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void reviewerApprovalOverridesAndTracksDisagreement() throws Exception {
        String claimId = createAndAnalyze("POL-RV1"); // no facts -> HUMAN_REVIEW

        Map<String, Object> body = Map.of("reviewDecision", "APPROVE",
                "reason", "Verified supporting documents offline.");
        String resp = mvc.perform(post("/api/v1/claims/{id}/review", claimId)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.finalDecision", is("APPROVE")))
                .andExpect(jsonPath("$.previousAiDecision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.agreedWithAi", is(false)))
                .andExpect(jsonPath("$.reviewerId", is("rev-1")))
                .andReturn().getResponse().getContentAsString();
        String reviewId = om.readTree(resp).get("reviewId").asText();

        // Claim status now reflects the human's verdict.
        mvc.perform(get("/api/v1/claims/{id}", claimId))
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Retrievable by id and in the list.
        mvc.perform(get("/api/v1/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId", is("rev-1")));
        mvc.perform(get("/api/v1/reviews").param("reviewerId", "rev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewerId", is("rev-1")));
    }

    @Test
    void requestInformationLeavesClaimInReview() throws Exception {
        String claimId = createAndAnalyze("POL-RV2");

        Map<String, Object> body = Map.of("reviewDecision", "REQUEST_INFORMATION");
        mvc.perform(post("/api/v1/claims/{id}/review", claimId)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.finalDecision").doesNotExist());

        mvc.perform(get("/api/v1/claims/{id}", claimId))
                .andExpect(jsonPath("$.status", is("HUMAN_REVIEW")));
    }

    @Test
    void missingReviewDecisionIsRejected() throws Exception {
        String claimId = createAndAnalyze("POL-RV3");
        Map<String, Object> body = Map.of("reason", "no decision provided");
        mvc.perform(post("/api/v1/claims/{id}/review", claimId)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    @Test
    void unknownReviewReturns404() throws Exception {
        mvc.perform(get("/api/v1/reviews/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("REVIEW_NOT_FOUND")));
    }

    private String createAndAnalyze(String policyNumber) throws Exception {
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
        String claimId = om.readTree(resp).get("claimId").asText();

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId)).andExpect(status().isOk());
        return claimId;
    }
}
