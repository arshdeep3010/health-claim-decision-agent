package com.healthclaim.decision.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.config.MercuryProperties;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.exception.DecisionProcessingException;
import com.healthclaim.decision.rules.DeterministicOutcome;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.policy.domain.PolicyStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MercuryDecisionProvider}: it parses the structured
 * decision JSON, keeps confidence only when valid, and surfaces malformed output
 * as a processing exception (which the orchestrator maps to HUMAN_REVIEW).
 */
class MercuryDecisionProviderTest {

    private final ObjectMapper om = new ObjectMapper();
    private final MercuryProperties props =
            new MercuryProperties("openrouter", "https://x", "k", "mercury-2.5", 30000, 2, 300, "v1");

    private MercuryDecisionProvider provider(String content) {
        ChatLlmProvider chat = Mockito.mock(ChatLlmProvider.class);
        when(chat.providerId()).thenReturn("openrouter");
        when(chat.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(content, "mercury-2.5", 5, 3, "stop", 12, 0));
        return new MercuryDecisionProvider(chat, om, props);
    }

    @Test
    void parsesValidDecision() {
        AiDecision ai = provider("""
                {"decision":"APPROVE","confidence":0.88,"reasonCodes":["COVERED"],
                 "summary":"Covered inpatient procedure.","uncertainties":[]}""")
                .decide(context());

        assertThat(ai.decision()).isEqualTo(DecisionType.APPROVE);
        assertThat(ai.confidence()).isEqualTo(0.88);
        assertThat(ai.reasonCodes()).contains("COVERED");
        assertThat(ai.model()).isEqualTo("mercury-2.5");
    }

    @Test
    void dropsOutOfRangeConfidence() {
        AiDecision ai = provider("""
                {"decision":"HUMAN_REVIEW","confidence":5,"reasonCodes":[],"summary":"x","uncertainties":[]}""")
                .decide(context());
        assertThat(ai.confidence()).isNull();
    }

    @Test
    void malformedJsonThrows() {
        assertThatThrownBy(() -> provider("not json {").decide(context()))
                .isInstanceOf(DecisionProcessingException.class);
    }

    @Test
    void invalidDecisionEnumThrows() {
        assertThatThrownBy(() -> provider("""
                {"decision":"MAYBE","confidence":null,"reasonCodes":[],"summary":"x","uncertainties":[]}""")
                .decide(context()))
                .isInstanceOf(DecisionProcessingException.class);
    }

    @Test
    void providerFailurePropagates() {
        ChatLlmProvider chat = Mockito.mock(ChatLlmProvider.class);
        when(chat.providerId()).thenReturn("openrouter");
        when(chat.chat(any(ChatRequest.class))).thenThrow(new AiProviderException("down"));
        assertThatThrownBy(() -> new MercuryDecisionProvider(chat, om, props).decide(context()))
                .isInstanceOf(AiProviderException.class);
    }

    private static ClaimDecisionContext context() {
        ClaimSummary claim = new ClaimSummary(null, "CLM-1", "Hospital", "Gallstones",
                "Laparoscopic Cholecystectomy", "inpatient", LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(1), new BigDecimal("145000"), "INR");
        PolicyFacts policy = new PolicyFacts("P", PolicyStatus.ACTIVE, LocalDate.now().minusYears(1),
                LocalDate.now().plusYears(1), new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO,
                "INR", List.of(), Map.of());
        return new ClaimDecisionContext(claim, policy,
                List.of(new ClaimDecisionContext.FactEntry("diagnosis", "Gallstones", "KNOWN")),
                List.of(), List.of(RuleResult.allow("POLICY_ACTIVE", "ok")),
                DeterministicOutcome.ALLOW, List.of(), List.of());
    }
}
