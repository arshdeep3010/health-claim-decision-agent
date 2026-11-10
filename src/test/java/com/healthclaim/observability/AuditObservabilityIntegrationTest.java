package com.healthclaim.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class AuditObservabilityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void analyzeWritesAuditTrailAndReviewRecordsOverride() throws Exception {
        String claimId = createAndAnalyze("POL-AUD1"); // -> HUMAN_REVIEW (missing facts)

        // Analysis produced an auditable record with operational metadata.
        mvc.perform(get("/api/v1/claims/{id}/audit", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType", is("AI_DECISION")))
                .andExpect(jsonPath("$[0].finalDecision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$[0].traceId", notNullValue()))
                .andExpect(jsonPath("$[0].detail", containsString("ruleOutcome")));

        // A reviewer override appends a HUMAN_OVERRIDE record.
        Map<String, Object> review = Map.of("reviewerId", "rev-1", "reviewDecision", "APPROVE");
        mvc.perform(post("/api/v1/claims/{id}/review", claimId)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(review)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/claims/{id}/audit", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", hasItem("HUMAN_OVERRIDE")));
    }

    @Test
    void decisionDistributionMetricIsRecorded() throws Exception {
        createAndAnalyze("POL-AUD2"); // -> HUMAN_REVIEW

        // The decision-distribution counter is registered and incremented. The real
        // /actuator/prometheus endpoint exposes this (verified against a running
        // server); MockMvc does not map actuator scrape endpoints, so we assert the
        // metric via the MeterRegistry directly.
        double count = meterRegistry.get("healthclaim.decisions")
                .tag("decision", "HUMAN_REVIEW").counter().count();
        org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(1.0);

        org.assertj.core.api.Assertions.assertThat(
                meterRegistry.find("healthclaim.analyze.latency").timer()).isNotNull();
    }

    @Test
    void auditForUnknownClaimReturns404() throws Exception {
        mvc.perform(get("/api/v1/claims/{id}/audit", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("CLAIM_NOT_FOUND")));
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
