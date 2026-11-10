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
import com.healthclaim.rag.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When RAG retrieval fails on an otherwise-allowable claim, the decision must be
 * HUMAN_REVIEW (never an unsupported auto approve/reject). §22.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class DecisionRagFailureIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private ClaimFactRepository claimFactRepository;

    @MockitoBean
    private RetrievalService retrievalService;

    @BeforeEach
    void ragDown() {
        when(retrievalService.retrieve(any(), any(), any()))
                .thenThrow(new RuntimeException("vector store unavailable"));
    }

    @Test
    void ragFailureOnAllowableClaimRoutesToReview() throws Exception {
        String claimId = createClaim("POL-RAGF");
        // Clean facts -> deterministic ALLOW, so the pipeline attempts RAG (which fails).
        seed(claimId, FactKey.DIAGNOSIS, "Gallstones");
        seed(claimId, FactKey.PROCEDURE, "Laparoscopic Cholecystectomy");
        seed(claimId, FactKey.PRE_EXISTING_CONDITION, "false");

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("RAG_FAILURE")));
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

    private void seed(String claimId, FactKey key, String value) {
        Claim claim = claimRepository.findById(UUID.fromString(claimId)).orElseThrow();
        ClaimFact fact = new ClaimFact();
        fact.setClaim(claim);
        fact.setFactKey(key.wire());
        fact.setFactValue(value);
        fact.setValueType(key.valueType());
        fact.setStatus(FactStatus.KNOWN);
        fact.setSource(FactSource.EXTRACTED);
        claimFactRepository.save(fact);
    }
}
