package com.healthclaim.decision.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplanationServiceTest {

    private static final ExplanationInput INPUT = new ExplanationInput("APPROVE", List.of("POLICY_ACTIVE"),
            List.of(), List.of(), List.of(), "Gallstones", "Laparoscopic Cholecystectomy", null, null, null);

    private static ExplanationService service(ChatLlmProvider chat, long deadlineMs) {
        return new ExplanationService(chat, new TemplateExplanationRenderer(), new ObjectMapper(), deadlineMs);
    }

    private static ChatLlmProvider configured() {
        ChatLlmProvider chat = mock(ChatLlmProvider.class);
        when(chat.isConfigured()).thenReturn(true);
        return chat;
    }

    @Test
    void usesTheLlmWhenItAnswersInTime() {
        ChatLlmProvider chat = configured();
        when(chat.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("**Approved.** Covered.", "some/model", 10, 20, "stop", 5, 0));

        ExplanationService.Explanation e = service(chat, 2_000).generate(INPUT);

        assertThat(e.source()).isEqualTo("llm");
        assertThat(e.model()).isEqualTo("some/model");
        assertThat(e.text()).isEqualTo("**Approved.** Covered.");
    }

    @Test
    void aSlowLlmIsAbandonedAtTheDeadlineAndTheTemplateIsUsed() {
        ChatLlmProvider chat = configured();
        when(chat.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            Thread.sleep(5_000); // like a free model kept alive with whitespace
            return new ChatResponse("too late", "slow/model", 1, 1, "stop", 5_000, 0);
        });

        long start = System.nanoTime();
        ExplanationService.Explanation e = service(chat, 300).generate(INPUT);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).isLessThan(2_000);
        assertThat(e.source()).isEqualTo("template");
        assertThat(e.model()).isNull();
        assertThat(e.text()).startsWith("**Approved.**");
    }

    @Test
    void aFailingLlmFallsBackToTheTemplate() {
        ChatLlmProvider chat = configured();
        when(chat.chat(any(ChatRequest.class))).thenThrow(new AiProviderException("AI provider unavailable: HTTP 503"));

        assertThat(service(chat, 2_000).generate(INPUT).source()).isEqualTo("template");
    }

    @Test
    void thePromptCarriesPlainReasonsAndPolicyWording() throws Exception {
        ExplanationInput input = new ExplanationInput("APPROVE", List.of("POLICY_ACTIVE", "DECISION_MODEL_APPROVE"),
                List.of(), List.of(), List.of(new ExplanationInput.Clause(1, "S", "3.1 Cholecystectomy is covered.")),
                "Gallstones", "Laparoscopic Cholecystectomy", "Decision model chose approve with 84% probability.",
                0.84, "parallel/decisions-fast");

        String prompt = service(configured(), 1_000).request(input).messages().get(1).content();

        assertThat(prompt).contains("The policy was active on the treatment date.")
                .contains("3.1 Cholecystectomy is covered.")
                .contains("Decision model chose approve with 84% probability.")
                .doesNotContain("DECISION_MODEL_APPROVE");
    }
}
