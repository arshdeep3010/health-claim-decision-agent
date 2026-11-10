package com.healthclaim.claim.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.ai.llm.JsonSchemaSpec;
import com.healthclaim.ai.metadata.AiCallMetadata;
import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.exception.ClaimExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/**
 * Converts unstructured claim information into validated structured facts using
 * the LLM with a strict JSON schema. The model's output is NEVER trusted blindly:
 * it is parsed, validated, and defensively normalized (e.g. a KNOWN status with a
 * missing/unparseable value is downgraded to UNKNOWN; confidence is kept only if
 * the model returns a valid [0,1] number).
 */
@Component
public class ClaimFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClaimFactExtractor.class);

    private static final int MAX_INPUT_CHARS = 8000;

    private static final String SYSTEM_PROMPT = """
            You extract structured facts from a synthetic health-insurance claim.
            Rules:
            - Use ONLY information explicitly present in the claim text. Never invent values.
            - If a fact is not stated, set status "UNKNOWN" and value null.
            - If the text gives conflicting information for a fact, set status "CONFLICTING".
            - Set status "KNOWN" only when the value is clearly supported.
            - preExistingCondition must be "true" or "false" ONLY if explicitly stated, else UNKNOWN.
            - hospitalizationDays is the number of nights, if determinable, else UNKNOWN.
            Return JSON that conforms exactly to the provided schema.""";

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "facts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {
                      "key": {"type": "string",
                        "enum": ["diagnosis","procedure","treatment","hospitalizationDays","claimAmount","preExistingCondition"]},
                      "value": {"type": ["string","null"]},
                      "status": {"type": "string", "enum": ["KNOWN","UNKNOWN","CONFLICTING"]},
                      "confidence": {"type": ["number","null"]}
                    },
                    "required": ["key","value","status","confidence"]
                  }
                }
              },
              "required": ["facts"]
            }""";

    private final ChatLlmProvider provider;
    private final ObjectMapper objectMapper;

    public ClaimFactExtractor(ChatLlmProvider provider, ObjectMapper objectMapper) {
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public List<ExtractedFact> extract(ClaimExtractionInput input) {
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(buildUserPrompt(input))),
                new JsonSchemaSpec("ClaimFacts", SCHEMA_JSON, true),
                4096, 0.0, "claim-extraction"); // headroom for reasoning tokens

        ChatResponse response = provider.chat(request); // may throw AiProviderException -> caller handles

        AiCallMetadata metadata = new AiCallMetadata(provider.providerId(), response.model(),
                "claim-extraction", response.latencyMs(), response.promptTokens(),
                response.completionTokens(), true, response.retryCount());
        log.info("AI extraction call: provider={} model={} latencyMs={} tokens={}/{} retries={}",
                metadata.provider(), metadata.model(), metadata.latencyMs(),
                metadata.promptTokens(), metadata.completionTokens(), metadata.retryCount());

        return validateAndNormalize(response.content());
    }

    private String buildUserPrompt(ClaimExtractionInput input) {
        StringBuilder sb = new StringBuilder("Claim information (synthetic):\n");
        appendIf(sb, "provider", input.provider());
        appendIf(sb, "diagnosis", input.diagnosis());
        appendIf(sb, "procedure", input.procedureName());
        appendIf(sb, "treatment", input.treatment());
        appendIf(sb, "hospitalizationStart", input.hospitalizationStart());
        appendIf(sb, "hospitalizationEnd", input.hospitalizationEnd());
        appendIf(sb, "claimAmount", input.claimAmount());
        appendIf(sb, "currency", input.currency());
        sb.append("\nExtract: diagnosis, procedure, treatment, hospitalizationDays, claimAmount, preExistingCondition.");
        String prompt = sb.toString();
        return prompt.length() > MAX_INPUT_CHARS ? prompt.substring(0, MAX_INPUT_CHARS) : prompt;
    }

    private static void appendIf(StringBuilder sb, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private List<ExtractedFact> validateAndNormalize(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new ClaimExtractionException("Model output was not valid JSON", e);
        }
        JsonNode facts = root.path("facts");
        if (!facts.isArray()) {
            throw new ClaimExtractionException("Model output missing 'facts' array");
        }

        EnumMap<FactKey, ExtractedFact> byKey = new EnumMap<>(FactKey.class);
        for (JsonNode node : facts) {
            Optional<FactKey> maybeKey = FactKey.fromWire(node.path("key").asText(null));
            if (maybeKey.isEmpty()) {
                continue; // ignore unknown keys rather than trust them
            }
            FactKey key = maybeKey.get();
            String value = node.hasNonNull("value") ? node.get("value").asText() : null;
            FactStatus status = parseStatus(node.path("status").asText(null));
            Double confidence = extractConfidence(node.get("confidence"));
            byKey.put(key, normalize(key, value, status, confidence));
        }
        // Any fact the model omitted is UNKNOWN (never silently fabricated).
        for (FactKey key : FactKey.values()) {
            byKey.putIfAbsent(key, ExtractedFact.unknown(key));
        }
        return List.copyOf(byKey.values());
    }

    private ExtractedFact normalize(FactKey key, String rawValue, FactStatus status, Double confidence) {
        String value = (rawValue == null || rawValue.isBlank()) ? null : rawValue.strip();

        if (status == FactStatus.KNOWN && value == null) {
            return new ExtractedFact(key, null, FactStatus.UNKNOWN, null);
        }
        if (value != null && status == FactStatus.KNOWN) {
            switch (key.valueType()) {
                case "NUMBER" -> {
                    String numeric = value.replaceAll("[,\\s]", "").replaceAll("(?i)^[a-z]{3}", "");
                    try {
                        Double.parseDouble(numeric);
                        value = numeric;
                    } catch (NumberFormatException e) {
                        return new ExtractedFact(key, null, FactStatus.UNKNOWN, null);
                    }
                }
                case "BOOLEAN" -> {
                    String normalized = normalizeBoolean(value);
                    if (normalized == null) {
                        return new ExtractedFact(key, null, FactStatus.UNKNOWN, null);
                    }
                    value = normalized;
                }
                default -> { /* STRING: keep as-is */ }
            }
        }
        return new ExtractedFact(key, value, status, confidence);
    }

    private static String normalizeBoolean(String value) {
        String v = value.toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("y")) {
            return "true";
        }
        if (v.equals("false") || v.equals("no") || v.equals("n")) {
            return "false";
        }
        return null;
    }

    private static FactStatus parseStatus(String raw) {
        if (raw == null) {
            return FactStatus.UNKNOWN;
        }
        try {
            return FactStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FactStatus.UNKNOWN;
        }
    }

    private static Double extractConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double c = node.asDouble();
        return (c >= 0.0 && c <= 1.0) ? c : null; // invalid -> treat as absent, never fabricate
    }
}
