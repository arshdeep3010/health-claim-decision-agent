package com.healthclaim.decision.provider;

import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.decision.config.DecisionModelProperties;
import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.rules.DeterministicOutcome;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.rag.evidence.EvidenceResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ParallelDecisionProviderTest {

    private static final String BASE = "https://router.example.test/api";

    private MockRestServiceServer server;

    private ParallelDecisionProvider provider() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ParallelDecisionProvider provider = new ParallelDecisionProvider(
                new DecisionModelProperties("decision-model", BASE, "test-key", "parallel/decisions-fast:free", 5000, 2),
                builder);
        provider.setBackOffMillis(1);
        return provider;
    }

    private static ClaimDecisionContext context() {
        ClaimSummary claim = new ClaimSummary(UUID.randomUUID(), "CLM-T", "Synthetic Hospital", "Kidney stones",
                "Ureteroscopy", "2-day inpatient", LocalDate.now().minusDays(3), LocalDate.now().minusDays(1),
                new BigDecimal("110000"), "INR");
        PolicyFacts policy = new PolicyFacts("POL-T", PolicyStatus.ACTIVE, LocalDate.now().minusYears(1),
                LocalDate.now().plusYears(1), new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.TEN, "INR",
                List.of("Cosmetic surgery"), Map.of("GENERAL", 1));
        EvidenceResult evidence = new EvidenceResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "Covered procedures", null, "3.5 Ureteroscopy is covered as an inpatient procedure.", 0.31);
        return new ClaimDecisionContext(claim, policy, List.of(), List.of(evidence),
                List.of(RuleResult.allow("POLICY_ACTIVE", "ok")), DeterministicOutcome.ALLOW, List.of(), List.of());
    }

    private static String answer(String choice, double approve, double review, double reject, String annotations) {
        return """
                {"model":"parallel/decisions-fast-20261106",
                 "answers":{"decision":{"type":"choice","choice":"%s",
                   "probabilities":{"APPROVE":%s,"HUMAN_REVIEW":%s,"REJECT":%s}}},
                 "annotations":%s,
                 "usage":{"input_tokens":3210,"output_tokens":0,"cost":0}}
                """.formatted(choice, approve, review, reject, annotations);
    }

    @Test
    void sendsTheEvidenceAsStateAndTheOutcomesAsAChoiceQuestion() {
        ParallelDecisionProvider provider = provider();
        server.expect(requestTo(BASE + "/alpha/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("parallel/decisions-fast:free"))
                .andExpect(jsonPath("$.state.claim.procedure").value("Ureteroscopy"))
                .andExpect(jsonPath("$.state.policy.coverageLimit").value(1000000))
                .andExpect(jsonPath("$.state.policyEvidence[0].text").value("3.5 Ureteroscopy is covered as an inpatient procedure."))
                .andExpect(jsonPath("$.state.ruleReasonCodes[0]").value("POLICY_ACTIVE"))
                .andExpect(jsonPath("$.questions.decision.type").value("choice"))
                .andExpect(jsonPath("$.questions.decision.criteria.APPROVE").exists())
                .andExpect(jsonPath("$.questions.decision.criteria.HUMAN_REVIEW").exists())
                .andExpect(jsonPath("$.questions.decision.criteria.REJECT").exists())
                .andRespond(withSuccess(answer("APPROVE", 0.99, 0.008, 0.002, "[]"), MediaType.APPLICATION_JSON));

        AiDecision decision = provider.decide(context());

        assertThat(decision.decision()).isEqualTo(DecisionType.APPROVE);
        assertThat(decision.confidence()).isEqualTo(0.99);
        assertThat(decision.model()).isEqualTo("parallel/decisions-fast-20261106");
        assertThat(decision.reasonCodes()).containsExactly("DECISION_MODEL_APPROVE");
        assertThat(decision.summary()).contains("approve with 99% probability");
        assertThat(decision.extras()).containsEntry("engine", "decision-model");
        @SuppressWarnings("unchecked")
        Map<String, Double> probabilities = (Map<String, Double>) decision.extras().get("probabilities");
        assertThat(probabilities).containsEntry("HUMAN_REVIEW", 0.008).containsEntry("REJECT", 0.002);
        assertThat(decision.uncertainties()).isEmpty();
        server.verify();
    }

    @Test
    void reportsWebSourcesAndARunnerUp() {
        ParallelDecisionProvider provider = provider();
        String annotations = """
                [{"type":"url_citation","url_citation":{"url":"https://example.org/policy.pdf","title":"Some policy","content":"..."}}]
                """;
        server.expect(requestTo(BASE + "/alpha/decisions"))
                .andRespond(withSuccess(answer("REJECT", 0.05, 0.11, 0.84, annotations), MediaType.APPLICATION_JSON));

        AiDecision decision = provider.decide(context());

        assertThat(decision.decision()).isEqualTo(DecisionType.REJECT);
        assertThat(decision.confidence()).isEqualTo(0.84);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> sources = (List<Map<String, String>>) decision.extras().get("sources");
        assertThat(sources).singleElement().satisfies(s -> {
            assertThat(s).containsEntry("url", "https://example.org/policy.pdf");
            assertThat(s).containsEntry("title", "Some policy");
        });
        assertThat(decision.uncertainties()).anySatisfy(u -> assertThat(u).contains("1 web source"));
        server.verify();
    }

    @Test
    void aRefusalIsAProviderFailure() {
        ParallelDecisionProvider provider = provider();
        server.expect(requestTo(BASE + "/alpha/decisions"))
                .andRespond(withSuccess("{\"answers\":{\"decision\":{\"type\":\"refusal\"}}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.decide(context()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void retriesServerErrorsButNotClientErrors() {
        ParallelDecisionProvider provider = provider();
        server.expect(requestTo(BASE + "/alpha/decisions")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE + "/alpha/decisions"))
                .andRespond(withSuccess(answer("HUMAN_REVIEW", 0.1, 0.82, 0.08, "[]"), MediaType.APPLICATION_JSON));
        assertThat(provider.decide(context()).decision()).isEqualTo(DecisionType.HUMAN_REVIEW);
        server.verify();

        ParallelDecisionProvider other = provider();
        server.expect(requestTo(BASE + "/alpha/decisions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Rate limit exceeded: free-models-per-day\"}}"));
        assertThatThrownBy(() -> other.decide(context()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("free-models-per-day");
        server.verify();
    }

    @Test
    void describesItselfForTheLiveStream() {
        ParallelDecisionProvider provider = provider();
        assertThat(provider.engine()).isEqualTo("decision-model");
        assertThat(provider.modelName()).isEqualTo("parallel/decisions-fast:free");
        assertThat(provider.instructions()).contains("SYNTHETIC").contains("APPROVE:").contains("REJECT:");
    }
}
