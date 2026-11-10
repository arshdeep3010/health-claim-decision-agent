package com.healthclaim.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.FactSource;
import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.claim.repository.ClaimFactRepository;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.provider.AiDecision;
import com.healthclaim.decision.provider.DecisionProvider;
import com.healthclaim.policy.PolicyApiIntegrationTest;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decision pipeline with a mocked {@link DecisionProvider}, exercising the
 * AI-assisted branch (reached only when deterministic rules ALLOW) and the safety
 * constraints the orchestrator applies on top of the AI's judgment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class DecisionWithAiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private ClaimFactRepository claimFactRepository;

    @MockitoBean
    private DecisionProvider decisionProvider;

    @Test
    void aiApproveLeadsToApprove() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.92, "AI_COVERED"));
        String claimId = cleanClaim("POL-AI1");

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("APPROVE")))
                .andExpect(jsonPath("$.confidence", is(0.92)))
                .andExpect(jsonPath("$.reasonCodes", hasItem("AI_COVERED")));
    }

    @Test
    void lowConfidenceApproveIsDowngradedToReview() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.2, "AI_COVERED"));
        String claimId = cleanClaim("POL-AI2");

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("LOW_AI_CONFIDENCE")));
    }

    @Test
    void aiRejectWithoutDeterministicBasisGoesToReview() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.REJECT, 0.9, "AI_NOT_COVERED"));
        String claimId = cleanClaim("POL-AI3");

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS")));
    }

    @Test
    void aiFailureRoutesToReview() throws Exception {
        when(decisionProvider.decide(any())).thenThrow(new AiProviderException("provider down"));
        String claimId = cleanClaim("POL-AI4");

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.reasonCodes", hasItem("AI_PROVIDER_FAILURE")));
    }

    @Test
    void storedDecisionKeepsTheAiSummaryAndReanalysisDropsAStaleExplanation() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.9, "AI_COVERED"));
        String claimId = cleanClaim("POL-AI-SUMMARY");
        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId)).andExpect(status().isOk());

        // The summary is persisted, not only returned by /analyze.
        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", is("summary")));

        mvc.perform(post("/api/v1/claims/{id}/explain", claimId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(jsonPath("$.explanation").isNotEmpty());

        // A new analysis may reach a different verdict, so the old explanation must not carry over.
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.2, "AI_UNSURE"));
        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")));
        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(jsonPath("$.decision", is("HUMAN_REVIEW")))
                .andExpect(jsonPath("$.explanation").value(nullValue()));
    }

    // --- streamed analysis (SSE) ---

    @Test
    void streamReportsEveryStageInOrderThenTheDecision() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.92, "AI_COVERED"));
        String claimId = cleanClaim("POL-AI5");

        List<SseEvent> events = stream(claimId);

        List<String> finished = events.stream()
                .filter(e -> e.name().equals("stage") && !e.data().get("status").asText().equals("STARTED"))
                .map(e -> e.data().get("stage").asText()).toList();
        assertThat(finished).containsExactly("AUTH", "LOAD", "RETRIEVE", "RULES", "AI", "GUARDRAILS", "PERSIST");

        JsonNode ai = stageEvent(events, "AI", "COMPLETED");
        assertThat(ai.at("/data/suggestion").asText()).isEqualTo("APPROVE");
        assertThat(ai.at("/data/model").asText()).isEqualTo("mock-model");
        assertThat(ai.at("/data/summary").asText()).isEqualTo("summary");
        assertThat(ai.at("/data/reasonCodes").toString()).contains("AI_COVERED");

        // The AI's input is streamed as the exact payload the provider is given to compare against.
        JsonNode aiStart = stageEvent(events, "AI", "STARTED");
        assertThat(aiStart.at("/data/instructions").asText()).contains("SYNTHETIC");
        assertThat(aiStart.at("/data/input/claim/diagnosis").asText()).isEqualTo("Gallstones");
        assertThat(aiStart.at("/data/input/claim/procedure").asText()).isEqualTo("Laparoscopic Cholecystectomy");
        assertThat(aiStart.at("/data/input/policy/coverageLimit").decimalValue()).isEqualByComparingTo("500000");
        assertThat(aiStart.at("/data/input/ruleReasonCodes").toString()).contains("POLICY_ACTIVE");
        assertThat(stageEvent(events, "RULES", "COMPLETED").at("/data/outcome").asText()).isEqualTo("ALLOW");
        assertThat(stageEvent(events, "AUTH", "COMPLETED").at("/data/roles").toString()).contains("ADMIN");

        SseEvent last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("decision");
        assertThat(last.data().get("decision").asText()).isEqualTo("APPROVE");
        assertThat(stageEvent(events, "PERSIST", "COMPLETED").at("/data/decisionId").asText())
                .isEqualTo(last.data().get("decisionId").asText());
    }

    @Test
    void streamShowsAiSkippedWhenRulesDecide() throws Exception {
        String claimId = cleanClaim("POL-AI6", false); // no pre-existing declaration -> rules REVIEW

        List<SseEvent> events = stream(claimId);

        assertThat(stageEvent(events, "RULES", "COMPLETED").at("/data/flagged").toString())
                .contains("PRE_EXISTING_STATUS_UNKNOWN");
        assertThat(stageEvent(events, "AI", "SKIPPED").at("/data/reason").asText()).isEqualTo("RULES_DECIDED");
        assertThat(events.stream().noneMatch(e -> e.name().equals("stage")
                && e.data().get("stage").asText().equals("AI") && e.data().get("status").asText().equals("STARTED")))
                .isTrue();
        assertThat(events.get(events.size() - 1).data().get("decision").asText()).isEqualTo("HUMAN_REVIEW");
        verifyNoInteractions(decisionProvider);
    }

    @Test
    void streamForUnknownClaimEndsWithErrorEvent() throws Exception {
        List<SseEvent> events = stream(UUID.randomUUID().toString());

        assertThat(stageEvent(events, "LOAD", "FAILED")).isNotNull();
        SseEvent last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("error");
        assertThat(last.data().get("status").asInt()).isEqualTo(404);
    }

    @Test
    void reembeddingPolicyWordingKeepsEarlierDecisionEvidence() throws Exception {
        when(decisionProvider.decide(any())).thenReturn(ai(DecisionType.APPROVE, 0.9, "covered"));
        String claimId = cleanClaim("POL-AI-REEMBED");
        String policyId = policyIdFor("POL-AI-REEMBED");
        mvc.perform(multipart("/api/v1/policies/{id}/documents", policyId).file(new MockMultipartFile(
                        "file", "policy.pdf", "application/pdf", PolicyApiIntegrationTest.samplePolicyPdf())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/policies/{id}/ingest", policyId)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportingEvidence[0].chunkId").isNotEmpty());

        // Re-embedding replaces the chunks a past decision cited; that decision keeps its evidence snapshot.
        mvc.perform(post("/api/v1/policies/{id}/ingest", policyId).param("reembed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed", is(1)));

        mvc.perform(get("/api/v1/claims/{id}/decision", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportingEvidence[0].snippet").isNotEmpty())
                .andExpect(jsonPath("$.supportingEvidence[0].chunkId").value(nullValue()));
    }

    private String policyIdFor(String policyNumber) throws Exception {
        String body = mvc.perform(get("/api/v1/policies").param("size", "200"))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode p : om.readTree(body).get("content")) {
            if (p.get("policyNumber").asText().equals(policyNumber)) {
                return p.get("policyId").asText();
            }
        }
        throw new AssertionError("Policy " + policyNumber + " not found");
    }

    private record SseEvent(String name, JsonNode data) { }

    private List<SseEvent> stream(String claimId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/claims/{id}/analyze/stream", claimId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(15_000); // returns once the emitter completes
        String body = result.getResponse().getContentAsString();

        List<SseEvent> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (name != null) events.add(new SseEvent(name, om.readTree(data.toString())));
        }
        return events;
    }

    private static JsonNode stageEvent(List<SseEvent> events, String stage, String status) {
        return events.stream()
                .filter(e -> e.name().equals("stage") && e.data().get("stage").asText().equals(stage)
                        && e.data().get("status").asText().equals(status))
                .map(SseEvent::data).findFirst()
                .orElseThrow(() -> new AssertionError("No " + stage + " " + status + " event in " + events));
    }

    // --- helpers ---

    private AiDecision ai(DecisionType decision, double confidence, String reason) {
        return new AiDecision(decision, confidence, List.of(reason), "summary", List.of(),
                "mock", "mock-model", 15L, 0);
    }

    private String cleanClaim(String policyNumber) throws Exception {
        return cleanClaim(policyNumber, true);
    }

    private String cleanClaim(String policyNumber, boolean declarePreExisting) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyNumber", policyNumber);
        policy.put("policyType", "INDIVIDUAL");
        policy.put("effectiveDate", LocalDate.now().minusYears(2).toString());
        policy.put("expiryDate", LocalDate.now().plusYears(1).toString());
        policy.put("coverageLimit", 500000);
        policy.put("currency", "INR");
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(policy))).andExpect(status().isCreated());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policyNumber", policyNumber);
        claim.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        claim.put("provider", "Synthetic Hospital");
        claim.put("diagnosis", "Gallstones");
        claim.put("procedureName", "Laparoscopic Cholecystectomy");
        claim.put("treatment", "inpatient hospitalization");
        claim.put("hospitalizationStart", LocalDate.now().minusDays(5).toString());
        claim.put("hospitalizationEnd", LocalDate.now().minusDays(2).toString());
        claim.put("claimAmount", 145000);
        claim.put("currency", "INR");
        String resp = mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claim)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String claimId = om.readTree(resp).get("claimId").asText();

        seed(claimId, FactKey.DIAGNOSIS, "Gallstones");
        seed(claimId, FactKey.PROCEDURE, "Laparoscopic Cholecystectomy");
        if (declarePreExisting) {
            seed(claimId, FactKey.PRE_EXISTING_CONDITION, "false");
        }
        return claimId;
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
