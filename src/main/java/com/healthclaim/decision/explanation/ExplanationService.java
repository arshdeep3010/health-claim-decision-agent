package com.healthclaim.decision.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Generates a human-readable explanation of a finalized decision (§18). It uses
 * the LLM when a chat provider is configured, from ONLY the structured decision
 * inputs, and falls back to a deterministic template offline or on any failure.
 * It never alters the decision, and it reports which of the two wrote the text.
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    static final String SYSTEM_PROMPT = """
            You explain a health-insurance claim decision to a claims reviewer. Use ONLY the
            JSON you are given. Do not change, question or re-decide the outcome, and never
            invent policy terms, amounts or facts. Plain English, no jargon, under 140 words,
            in exactly this shape (Markdown, no headings other than these bold labels):

            **<Approved | Rejected | Needs review>.** One sentence saying what was claimed and why.

            **Why**
            - 2 to 4 bullets in plain words: the policy checks that decided it, and what the
              policy wording says about this procedure (quote a few words from policyWording).
            - If aiJudge is present, one bullet with its verdict and probabilities.

            **Worth checking**
            - Only if there are uncertainties or missing information; otherwise omit this part.""";

    /** Who wrote an explanation: the LLM (with its model id) or the template fallback. */
    public record Explanation(String text, String source, String model) {

        public static Explanation llm(String text, String model) {
            return new Explanation(text, "llm", model);
        }

        public static Explanation template(String text) {
            return new Explanation(text, "template", null);
        }
    }

    /**
     * Runs the LLM call so it can be abandoned at the deadline. A socket read timeout is
     * not enough: OpenRouter keeps slow requests alive with whitespace, which resets it.
     */
    private static final ExecutorService LLM_CALLS = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "explanation-llm");
        t.setDaemon(true);
        return t;
    });

    private final ChatLlmProvider chatProvider;
    private final TemplateExplanationRenderer template;
    private final ObjectMapper objectMapper;
    private final long deadlineMs;

    public ExplanationService(ChatLlmProvider chatProvider,
                              TemplateExplanationRenderer template,
                              ObjectMapper objectMapper,
                              @Value("${app.explanation.deadline-ms:40000}") long deadlineMs) {
        this.chatProvider = chatProvider;
        this.template = template;
        this.objectMapper = objectMapper;
        this.deadlineMs = deadlineMs;
    }

    public Explanation generate(ExplanationInput input) {
        if (chatProvider.isConfigured()) {
            CompletableFuture<ChatResponse> call = null;
            try {
                ChatRequest request = request(input);
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                call = CompletableFuture.supplyAsync(() -> {
                    if (mdc != null) {
                        MDC.setContextMap(mdc); // keep the trace id on the worker's log lines
                    }
                    try {
                        return chatProvider.chat(request);
                    } finally {
                        MDC.clear();
                    }
                }, LLM_CALLS);
                ChatResponse response = call.get(deadlineMs, TimeUnit.MILLISECONDS);
                String content = response.content();
                if (content != null && !content.isBlank()) {
                    return Explanation.llm(content.strip(), response.model());
                }
                log.warn("LLM explanation was empty; using template fallback");
            } catch (TimeoutException e) {
                call.cancel(true);
                log.warn("LLM explanation took longer than {} ms; using template fallback", deadlineMs);
            } catch (ExecutionException e) {
                log.warn("LLM explanation failed; using template fallback: {}", e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("LLM explanation interrupted; using template fallback");
            } catch (Exception e) {
                log.warn("LLM explanation failed; using template fallback: {}", e.getMessage());
            }
        }
        return Explanation.template(template.render(input));
    }

    ChatRequest request(ExplanationInput input) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", input.decision());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("diagnosis", input.diagnosis());
        claim.put("procedure", input.procedureName());
        payload.put("claim", claim);

        payload.put("policyChecks", input.reasonCodes() == null ? List.of() : input.reasonCodes().stream()
                .filter(code -> !code.startsWith("DECISION_MODEL_"))
                .map(TemplateExplanationRenderer::describe)
                .toList());
        if (input.model() != null && input.summary() != null) {
            Map<String, Object> ai = new LinkedHashMap<>();
            ai.put("model", input.model());
            ai.put("verdict", input.summary());
            payload.put("aiJudge", ai);
        }
        payload.put("policyWording", input.evidence() == null ? List.of() : input.evidence().stream()
                .limit(4)
                .map(c -> Map.of("page", c.page() == null ? "?" : c.page(), "text", c.text()))
                .toList());
        payload.put("missingInformation", input.missingInformation());
        payload.put("uncertainties", input.uncertainties());

        String userPrompt = "Explain this decision:\n" + objectMapper.writeValueAsString(payload);
        return new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(userPrompt)),
                null, 4096, 0.2, "claim-explanation"); // headroom for reasoning tokens
    }
}
