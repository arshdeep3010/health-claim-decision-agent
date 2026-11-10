package com.healthclaim.ai.mercury;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.config.MercuryProperties;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.ai.exception.PermanentAiException;
import com.healthclaim.ai.exception.TransientAiException;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.ai.llm.JsonSchemaSpec;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@link ChatLlmProvider} for Mercury via any OpenAI-compatible endpoint
 * (OpenRouter or Inception). Wraps the call with:
 * <ul>
 *   <li>HTTP connect/read timeouts (hard bound on each attempt),</li>
 *   <li>retry with exponential backoff on transient failures only (5xx/timeout/IO),</li>
 *   <li>a shared circuit breaker that fails fast when the provider is unhealthy.</li>
 * </ul>
 * 4xx responses are permanent and never retried. All failures surface as
 * {@link AiProviderException} so callers route to HUMAN_REVIEW.
 */
public class MercuryChatProvider implements ChatLlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MercuryChatProvider.class);

    private final MercuryProperties props;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final RestClient restClient;
    private final RetryConfig retryConfig;

    public MercuryChatProvider(MercuryProperties props,
                               ObjectMapper objectMapper,
                               CircuitBreaker circuitBreaker,
                               RestClient restClient) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.restClient = restClient;
        this.retryConfig = RetryConfig.custom()
                .maxAttempts(Math.max(1, props.maxRetries() + 1))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(Math.max(1, props.retryBackoffMs())), 2.0))
                .retryExceptions(TransientAiException.class)
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long start = System.nanoTime();
        AtomicInteger retries = new AtomicInteger();

        Retry retry = Retry.of("mercury-call", retryConfig);
        retry.getEventPublisher().onRetry(e -> retries.incrementAndGet());

        // Circuit breaker is the OUTERMOST layer: when open it fails fast without
        // entering the retry loop. Retry wraps the raw call (transient-only).
        Supplier<ParsedChat> withRetry = Retry.decorateSupplier(retry, () -> doCall(request));
        Supplier<ParsedChat> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);

        try {
            ParsedChat parsed = decorated.get();
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new ChatResponse(parsed.content(), parsed.model(), parsed.promptTokens(),
                    parsed.completionTokens(), parsed.finishReason(), latency, retries.get());
        } catch (CallNotPermittedException e) {
            throw new AiProviderException("AI provider circuit is open; failing fast", e);
        } catch (PermanentAiException e) {
            throw new AiProviderException("AI provider rejected the request: " + e.getMessage(), e);
        } catch (TransientAiException e) {
            throw new AiProviderException("AI provider unavailable after retries", e);
        }
    }

    private ParsedChat doCall(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        String json;
        try {
            json = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new PermanentAiException("HTTP " + res.getStatusCode().value());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new TransientAiException("HTTP " + res.getStatusCode().value());
                    })
                    .body(String.class);
        } catch (RestClientException e) {
            // Connect/read timeout, I/O error, or response-extraction failure ->
            // transient, safe to retry. (Our own Transient/PermanentAiException are
            // not RestClientException subclasses, so they propagate unaffected.)
            throw new TransientAiException("I/O error calling AI provider", e);
        }
        return parse(json);
    }

    private Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());

        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            messages.add(Map.of("role", m.role().wireValue(), "content", m.content()));
        }
        body.put("messages", messages);

        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        JsonSchemaSpec schema = request.jsonSchema();
        if (schema != null) {
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", schema.name());
            jsonSchema.put("strict", schema.strict());
            jsonSchema.put("schema", parseSchema(schema.schemaJson()));
            body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        }
        return body;
    }

    private Object parseSchema(String schemaJson) {
        try {
            return objectMapper.readValue(schemaJson, Map.class);
        } catch (Exception e) {
            throw new PermanentAiException("Invalid JSON schema supplied", e);
        }
    }

    private ParsedChat parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new TransientAiException("AI response had no choices");
            }
            JsonNode first = choices.get(0);
            String content = first.path("message").path("content").asText(null);
            if (content == null) {
                throw new TransientAiException("AI response had no message content");
            }
            String model = root.path("model").asText(props.model());
            String finishReason = first.path("finish_reason").asText(null);
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
            return new ParsedChat(content, model, promptTokens, completionTokens, finishReason);
        } catch (TransientAiException | PermanentAiException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAiException("Could not parse AI response", e);
        }
    }

    @Override
    public String providerId() {
        return props.provider();
    }

    private record ParsedChat(String content, String model, Integer promptTokens,
                              Integer completionTokens, String finishReason) {
    }
}
