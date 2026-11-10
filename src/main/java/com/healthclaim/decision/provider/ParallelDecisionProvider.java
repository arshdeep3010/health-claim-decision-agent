package com.healthclaim.decision.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.decision.config.DecisionModelProperties;
import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.domain.DecisionType;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Judges a claim with a dedicated <b>decision model</b> (Parallel Decisions, through
 * OpenRouter's {@code /alpha/decisions} endpoint) instead of a text-generating LLM.
 *
 * <p>The model receives the same controlled package as the LLM path
 * ({@link DecisionPromptPayload}) as its {@code state}, plus one {@code choice}
 * question whose options are the three outcomes. It answers with the chosen outcome
 * and a probability for each, in one to two seconds. The probability of the chosen
 * outcome becomes the confidence the guardrails check.
 *
 * <p>Decision models can also consult the live web. Any sources it used are returned
 * and shown, and the guardrails still apply: a REJECT with no rule basis goes to a
 * person, whatever the model's confidence.
 */
public class ParallelDecisionProvider implements DecisionProvider {

    static final String QUESTION = "decision";

    static final String INSTRUCTIONS = "This is a SYNTHETIC health-insurance claim. Its policy is fictional "
            + "and exists only in the state. Decide the claim using only the policy terms and the policy "
            + "evidence in the state. Deterministic rules already found no hard objection (policy active, "
            + "nothing excluded by name, within the limit, waiting periods over, required facts present).";

    static final Map<String, String> CRITERIA = criteria();

    private final DecisionModelProperties properties;
    private final RestClient client;
    private long backOffMillis = 400;

    /** The builder should already carry the HTTP timeouts (see {@link #withTimeouts}). */
    public ParallelDecisionProvider(DecisionModelProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    /** A copy of {@code builder} with connect/read timeouts from the properties. */
    public static RestClient.Builder withTimeouts(RestClient.Builder builder, DecisionModelProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(properties.timeoutMs(), 10_000));
        factory.setReadTimeout(properties.timeoutMs());
        return builder.clone().requestFactory(factory);
    }

    private static Map<String, String> criteria() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("APPROVE", "The policy evidence clearly covers this diagnosis and procedure, and nothing in the "
                + "evidence contradicts coverage.");
        c.put("HUMAN_REVIEW", "The evidence is ambiguous, incomplete or does not clearly cover the claim, so a "
                + "person should decide.");
        c.put("REJECT", "The policy evidence explicitly excludes or contradicts coverage for this claim.");
        return Map.copyOf(c);
    }

    /** Shortens the retry pause (tests). */
    void setBackOffMillis(long backOffMillis) {
        this.backOffMillis = backOffMillis;
    }

    @Override
    public AiDecision decide(ClaimDecisionContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("note", "SYNTHETIC claim. The policy is fictional and exists only in this state.");
        state.putAll(DecisionPromptPayload.build(context));

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", INSTRUCTIONS);
        question.put("criteria", orderedCriteria());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("state", state);
        body.put("questions", Map.of(QUESTION, question));

        long start = System.nanoTime();
        int retries = 0;
        JsonNode response;
        for (int attempt = 1; ; attempt++) {
            try {
                response = client.post()
                        .uri("/alpha/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                break;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is5xxServerError() && attempt <= properties.maxRetries()) {
                    retries++;
                    backOff(attempt);
                    continue;
                }
                throw new AiProviderException("Decision model returned HTTP " + e.getStatusCode().value()
                        + ": " + abbreviate(e.getResponseBodyAsString()), e);
            } catch (RestClientException e) {
                if (attempt <= properties.maxRetries()) {
                    retries++;
                    backOff(attempt);
                    continue;
                }
                throw new AiProviderException("Decision model call failed: " + e.getMessage(), e);
            }
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return parse(response, latencyMs, retries);
    }

    private AiDecision parse(JsonNode response, long latencyMs, int retries) {
        if (response == null) {
            throw new AiProviderException("Decision model returned an empty response");
        }
        JsonNode answer = response.path("answers").path(QUESTION);
        String type = answer.path("type").asText("");
        if ("refusal".equals(type)) {
            throw new AiProviderException("Decision model refused to answer");
        }
        if (!"choice".equals(type)) {
            throw new AiProviderException("Decision model returned an unexpected answer type: '" + type + "'");
        }
        DecisionType decision;
        try {
            decision = DecisionType.valueOf(answer.path("choice").asText());
        } catch (IllegalArgumentException e) {
            throw new AiProviderException("Decision model chose an unknown outcome: " + answer.path("choice"));
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (String option : List.of("APPROVE", "HUMAN_REVIEW", "REJECT")) {
            JsonNode p = answer.path("probabilities").path(option);
            if (p.isNumber()) {
                probabilities.put(option, p.asDouble());
            }
        }
        Double chosen = probabilities.get(decision.name());
        Double confidence = chosen != null && chosen >= 0.0 && chosen <= 1.0 ? chosen : null;

        List<Map<String, String>> sources = new ArrayList<>();
        JsonNode annotations = response.has("citations") ? response.path("citations") : response.path("annotations");
        for (JsonNode a : annotations) {
            JsonNode c = a.has("url_citation") ? a.path("url_citation") : a;
            String url = c.path("url").asText("");
            if (!url.isBlank() && sources.size() < 10) {
                Map<String, String> s = new LinkedHashMap<>();
                s.put("url", url);
                s.put("title", c.path("title").asText(""));
                sources.add(s);
            }
        }

        List<String> uncertainties = new ArrayList<>();
        probabilities.entrySet().stream()
                .filter(e -> !e.getKey().equals(decision.name()))
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= 0.15)
                .ifPresent(e -> uncertainties.add("Runner-up: " + label(e.getKey()) + " at " + pct(e.getValue()) + "."));
        if (!sources.isEmpty()) {
            uncertainties.add("The decision model consulted " + sources.size()
                    + " web source(s); only the policy evidence should decide a fictional policy.");
        }

        String summary = "Decision model chose " + label(decision.name())
                + (confidence != null ? " with " + pct(confidence) + " probability" : "")
                + " (" + describe(probabilities) + ").";

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("engine", engine());
        extras.put("probabilities", probabilities);
        extras.put("sources", sources);

        String model = response.path("model").asText(properties.model());
        return new AiDecision(decision, confidence, List.of("DECISION_MODEL_" + decision.name()), summary,
                uncertainties, providerId(), model, latencyMs, retries, extras);
    }

    private Map<String, String> orderedCriteria() {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String option : List.of("APPROVE", "HUMAN_REVIEW", "REJECT")) {
            ordered.put(option, CRITERIA.get(option));
        }
        return ordered;
    }

    private static String describe(Map<String, Double> probabilities) {
        List<String> parts = new ArrayList<>();
        probabilities.forEach((k, v) -> parts.add(label(k) + " " + pct(v)));
        return String.join(", ", parts);
    }

    private static String label(String outcome) {
        return outcome.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String pct(double p) {
        return Math.round(p * 100) + "%";
    }

    private void backOff(int attempt) {
        try {
            Thread.sleep(backOffMillis * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Interrupted while retrying the decision model", e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }

    @Override
    public String providerId() {
        return "openrouter";
    }

    @Override
    public String instructions() {
        StringBuilder text = new StringBuilder(INSTRUCTIONS).append("\n\nChoose one outcome:");
        orderedCriteria().forEach((k, v) -> text.append("\n- ").append(k).append(": ").append(v));
        return text.toString();
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    @Override
    public String engine() {
        return "decision-model";
    }
}
