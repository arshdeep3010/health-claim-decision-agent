package com.healthclaim.decision.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a human-readable explanation of a finalized decision (§18). It uses
 * the LLM when a chat provider is configured, from ONLY the structured decision
 * inputs, and falls back to a deterministic template offline or on any failure.
 * It never alters the decision.
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private static final String SYSTEM_PROMPT = """
            Write a concise, factual explanation of a health-insurance claim decision for a
            human reviewer, based ONLY on the provided decision, reason codes, policy evidence,
            and facts. Do NOT change, second-guess, or re-decide the outcome. Do NOT invent
            policy terms or facts. Prefer short bullet points. Keep it under 150 words.""";

    private final ChatLlmProvider chatProvider;
    private final TemplateExplanationRenderer template;
    private final ObjectMapper objectMapper;

    public ExplanationService(ChatLlmProvider chatProvider,
                              TemplateExplanationRenderer template,
                              ObjectMapper objectMapper) {
        this.chatProvider = chatProvider;
        this.template = template;
        this.objectMapper = objectMapper;
    }

    public String generate(ExplanationInput input) {
        if (chatProvider.isConfigured()) {
            try {
                return generateWithLlm(input);
            } catch (Exception e) {
                log.warn("LLM explanation failed; using template fallback: {}", e.getMessage());
            }
        }
        return template.render(input);
    }

    private String generateWithLlm(ExplanationInput input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", input.decision());
        payload.put("reasonCodes", input.reasonCodes());
        payload.put("policyEvidence", input.evidenceRefs());
        payload.put("missingInformation", input.missingInformation());
        payload.put("uncertainties", input.uncertainties());
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("diagnosis", input.diagnosis());
        claim.put("procedure", input.procedureName());
        payload.put("claim", claim);

        String userPrompt;
        try {
            userPrompt = "Explain this decision:\n" + objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return template.render(input);
        }

        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(userPrompt)),
                null, 4096, 0.2, "claim-explanation"); // headroom for reasoning tokens
        ChatResponse response = chatProvider.chat(request);
        String content = response.content();
        return (content == null || content.isBlank()) ? template.render(input) : content.strip();
    }
}
