package com.healthclaim.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.FactSource;
import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.claim.repository.ClaimFactRepository;
import com.healthclaim.claim.repository.ClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decision pipeline with the default rule-only provider (no AI, offline). Covers
 * the deterministic outcomes and idempotency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class DecisionAnalysisIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private ClaimFactRepository claimFactRepository;

    @Test
    void cleanClaimIsApproved() throws Exception {
        String claimId = setupClaim("POL-D1", "Laparoscopic Cholecystectomy", new BigDecimal("145000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("APPROVE")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("RULES_ONLY_NO_OBJECTION")));

        mvc.perform(get("/api/v1/claims/{id}", claimId))
                .andExpect(jsonPath("$.status", is("APPROVED")));
    }

    @Test
    void expiredPolicyIsRejected() throws Exception {
        String claimId = setupClaim("POL-D2", "Laparoscopic Cholecystectomy", new BigDecimal("1000"),
                new BigDecimal("500000"), null, LocalDate.now().minusDays(1));

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("REJECT")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("POLICY_EXPIRED")));
    }

    @Test
    void excludedProcedureIsRejected() throws Exception {
        String claimId = setupClaim("POL-D3", "Cosmetic surgery", new BigDecimal("1000"),
                new BigDecimal("500000"), "[\"Cosmetic surgery\"]", LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("REJECT")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("PROCEDURE_EXCLUDED")));
    }

    @Test
    void overLimitClaimGoesToReview() throws Exception {
        String claimId = setupClaim("POL-D4", "Laparoscopic Cholecystectomy", new BigDecimal("5000"),
                new BigDecimal("1000"), null, LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("CLAIM_EXCEEDS_COVERAGE_LIMIT")));
    }

    @Test
    void missingFactsGoToReview() throws Exception {
        String claimId = setupClaim("POL-D5", "Laparoscopic Cholecystectomy", new BigDecimal("1000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));
        // No facts seeded -> diagnosis/procedure unknown.

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("MISSING_REQUIRED_INFORMATION")));
    }

    @Test
    void conflictingFactsGoToReview() throws Exception {
        String claimId = setupClaim("POL-D6", "Laparoscopic Cholecystectomy", new BigDecimal("1000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);
        seedFact(claimId, FactKey.CLAIM_AMOUNT, "1", FactStatus.CONFLICTING);

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("CONFLICTING_INFORMATION")));
    }

    @Test
    void analyzeIsIdempotentWithKey() throws Exception {
        String claimId = setupClaim("POL-D7", "Laparoscopic Cholecystectomy", new BigDecimal("145000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);

        String first = mvc.perform(post("/api/v1/claims/{id}/analyze", claimId)
                        .header("Idempotency-Key", "key-123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/claims/{id}/analyze", claimId)
                        .header("Idempotency-Key", "key-123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstId = om.readTree(first).get("decisionId").asText();
        String secondId = om.readTree(second).get("decisionId").asText();
        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void reanalyzeWithoutKeyUpsertsSameDecision() throws Exception {
        String claimId = setupClaim("POL-D9", "Laparoscopic Cholecystectomy", new BigDecimal("145000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));
        seedCleanFacts(claimId);

        String first = mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // One decision per claim: re-analysis upserts the same row, not a duplicate.
        org.assertj.core.api.Assertions.assertThat(om.readTree(second).get("decisionId").asText())
                .isEqualTo(om.readTree(first).get("decisionId").asText());
    }

    @Test
    void decisionBeforeAnalyzeReturns404() throws Exception {
        String claimId = setupClaim("POL-D8", "Laparoscopic Cholecystectomy", new BigDecimal("1000"),
                new BigDecimal("500000"), null, LocalDate.now().plusYears(1));

        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("DECISION_NOT_FOUND")));
    }

    // --- helpers ---

    private String setupClaim(String policyNumber, String procedure, BigDecimal amount,
                              BigDecimal coverageLimit, String exclusionsJson, LocalDate expiry) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyNumber", policyNumber);
        policy.put("policyType", "INDIVIDUAL");
        policy.put("effectiveDate", LocalDate.now().minusYears(2).toString());
        policy.put("expiryDate", expiry.toString());
        policy.put("coverageLimit", coverageLimit);
        policy.put("currency", "INR");
        if (exclusionsJson != null) {
            policy.put("exclusions", exclusionsJson);
        }
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(policy))).andExpect(status().isCreated());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policyNumber", policyNumber);
        claim.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        claim.put("provider", "Synthetic Hospital");
        claim.put("diagnosis", "Gallstones");
        claim.put("procedureName", procedure);
        claim.put("treatment", "inpatient hospitalization");
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

    private void seedCleanFacts(String claimId) {
        seedFact(claimId, FactKey.DIAGNOSIS, "Gallstones", FactStatus.KNOWN);
        seedFact(claimId, FactKey.PROCEDURE, "Laparoscopic Cholecystectomy", FactStatus.KNOWN);
        seedFact(claimId, FactKey.PRE_EXISTING_CONDITION, "false", FactStatus.KNOWN);
    }

    private void seedFact(String claimId, FactKey key, String value, FactStatus statusValue) {
        Claim claim = claimRepository.findById(UUID.fromString(claimId)).orElseThrow();
        ClaimFact fact = claimFactRepository.findByClaimIdAndFactKey(claim.getId(), key.wire())
                .orElseGet(ClaimFact::new);
        fact.setClaim(claim);
        fact.setFactKey(key.wire());
        fact.setFactValue(value);
        fact.setValueType(key.valueType());
        fact.setStatus(statusValue);
        fact.setSource(FactSource.EXTRACTED);
        claimFactRepository.save(fact);
    }
}
