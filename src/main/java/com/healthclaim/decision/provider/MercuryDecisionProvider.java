package com.healthclaim.decision.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.config.MercuryProperties;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.ai.llm.JsonSchemaSpec;
import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.exception.DecisionProcessingException;

import java.util.ArrayList;
import java.util.List;

/**
 * Mercury-backed semantic decision via the OpenAI-compatible chat provider with a
 * strict JSON schema. Receives only the controlled {@link ClaimDecisionContext}.
 * Output is validated; confidence is kept only if the model returns a valid [0,1].
 */
public class MercuryDecisionProvider implements DecisionProvider {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "decision": {"type": "string", "enum": ["APPROVE","REJECT","HUMAN_REVIEW"]},
                "confidence": {"type": ["number","null"]},
                "reasonCodes": {"type": "array", "items": {"type": "string"}},
                "summary": {"type": "string"},
                "uncertainties": {"type": "array", "items": {"type": "string"}}
              },
              "required": ["decision","confidence","reasonCodes","summary","uncertainties"]
            }""";

    private final ChatLlmProvider chat;
    private final ObjectMapper objectMapper;
    private final MercuryProperties properties;

    public MercuryDecisionProvider(ChatLlmProvider chat, ObjectMapper objectMapper, MercuryProperties properties) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public AiDecision decide(ClaimDecisionContext context) {
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(DecisionPromptPayload.INSTRUCTIONS), ChatMessage.user(buildUserPrompt(context))),
                new JsonSchemaSpec("ClaimDecision", SCHEMA_JSON, true),
                4096, 0.0, "claim-decision"); // reasoning models (mercury-2.5) spend ~1k tokens thinking first

        ChatResponse response = chat.chat(request); // AiProviderException propagates to orchestrator
        return parse(response);
    }

    private String buildUserPrompt(ClaimDecisionContext ctx) {
        try {
            return "Decide this claim based on the following context:\n"
                    + objectMapper.writeValueAsString(DecisionPromptPayload.build(ctx));
        } catch (Exception e) {
            throw new DecisionProcessingException("Failed to serialize decision context", e);
        }
    }

    private AiDecision parse(ChatResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.content());
            DecisionType decision = DecisionType.valueOf(root.path("decision").asText());
            Double confidence = null;
            JsonNode conf = root.get("confidence");
            if (conf != null && conf.isNumber()) {
                double c = conf.asDouble();
                if (c >= 0.0 && c <= 1.0) {
                    confidence = c;
                }
            }
            List<String> reasonCodes = toStringList(root.get("reasonCodes"));
            List<String> uncertainties = toStringList(root.get("uncertainties"));
            String summary = root.path("summary").asText("");
            return new AiDecision(decision, confidence, reasonCodes, summary, uncertainties,
                    chat.providerId(), response.model(), response.latencyMs(), response.retryCount());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DecisionProcessingException("AI returned an invalid decision payload", e);
        } catch (Exception e) {
            throw new DecisionProcessingException("Could not parse AI decision", e);
        }
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    @Override
    public String providerId() {
        return properties.provider();
    }
}
