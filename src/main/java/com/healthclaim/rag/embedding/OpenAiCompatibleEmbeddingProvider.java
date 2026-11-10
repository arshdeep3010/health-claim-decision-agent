package com.healthclaim.rag.embedding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.healthclaim.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls an OpenAI-compatible {@code POST /embeddings} endpoint (OpenAI, NVIDIA NIM,
 * and similar). Activated only when {@code app.embedding.provider=openai-compatible},
 * so dev/CI (local provider) never requires network or keys.
 *
 * <p>Queries and passages are embedded separately so asymmetric retrieval models
 * receive the right {@code input_type}; for symmetric models leave the input types
 * blank and both paths send the plain OpenAI request.
 */
@Component
@ConditionalOnProperty(prefix = "app.embedding", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    /** Attempts per request: one call plus two retries on a 5xx or network error. */
    static final int MAX_ATTEMPTS = 3;

    private final EmbeddingProperties properties;
    private final RestClient client;
    private long backOffMillis = 500;

    public OpenAiCompatibleEmbeddingProvider(EmbeddingProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("EMBEDDING_BASE_URL is required when EMBEDDING_PROVIDER=openai-compatible");
        }
        if (!StringUtils.hasText(properties.model())) {
            throw new IllegalStateException("EMBEDDING_MODEL is required when EMBEDDING_PROVIDER=openai-compatible");
        }
        RestClient.Builder configured = builder.baseUrl(properties.baseUrl());
        if (StringUtils.hasText(properties.apiKey())) {
            configured = configured.defaultHeader("Authorization", "Bearer " + properties.apiKey());
        }
        this.client = configured.build();
    }

    /** Shortens the retry pause (tests). */
    void setBackOffMillis(long backOffMillis) {
        this.backOffMillis = backOffMillis;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** Unlabelled batch; asymmetric models treat it as passages. */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return embedPassages(texts);
    }

    @Override
    public float[] embedQuery(String query) {
        return call(List.of(query), properties.queryInputType()).get(0);
    }

    @Override
    public List<float[]> embedPassages(List<String> passages) {
        int size = Math.max(1, properties.batchSize());
        List<float[]> result = new ArrayList<>(passages.size());
        for (int from = 0; from < passages.size(); from += size) {
            result.addAll(call(passages.subList(from, Math.min(from + size, passages.size())),
                    properties.passageInputType()));
        }
        return result;
    }

    @Override
    public int dimension() {
        return properties.dim();
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    private List<float[]> call(List<String> texts, String inputType) {
        EmbeddingRequest request = new EmbeddingRequest(
                properties.model(),
                texts,
                "float",
                blankToNull(inputType),
                properties.requestDimensions() ? properties.dim() : null,
                blankToNull(properties.truncate()));
        EmbeddingResponse response = null;
        for (int attempt = 1; response == null; attempt++) {
            try {
                response = client.post()
                        .uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(EmbeddingResponse.class);
                if (response == null) {
                    throw new EmbeddingException("Embedding provider returned no data");
                }
            } catch (RestClientResponseException e) {
                // Hosted endpoints fail transiently with 5xx; a bad request (4xx) will not get better.
                if (e.getStatusCode().is5xxServerError() && attempt < MAX_ATTEMPTS) {
                    backOff(attempt);
                    continue;
                }
                // The body says why (unknown model, missing input_type, bad dimensions); never includes the key.
                throw new EmbeddingException("Embedding provider returned HTTP " + e.getStatusCode().value()
                        + ": " + abbreviate(e.getResponseBodyAsString()), e);
            } catch (RestClientException e) {
                if (attempt < MAX_ATTEMPTS) {
                    backOff(attempt);
                    continue;
                }
                throw new EmbeddingException("Embedding provider call failed", e);
            }
        }
        if (response.data() == null || response.data().size() != texts.size()) {
            throw new EmbeddingException("Embedding provider returned "
                    + (response.data() == null ? "no data" : response.data().size() + " vectors")
                    + " for " + texts.size() + " inputs");
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (EmbeddingResponse.Item item : response.data()) {
            float[] vector = toFloatArray(item.embedding());
            if (vector.length != properties.dim()) {
                throw new EmbeddingException("Embedding model returned dimension " + vector.length
                        + " but EMBEDDING_DIM is " + properties.dim());
            }
            result.add(vector);
        }
        return result;
    }

    private void backOff(int attempt) {
        try {
            Thread.sleep(backOffMillis * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while retrying the embedding provider", e);
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }

    private static float[] toFloatArray(List<Double> values) {
        if (values == null) {
            throw new EmbeddingException("Embedding item had no vector");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmbeddingRequest(
            String model,
            List<String> input,
            @JsonProperty("encoding_format") String encodingFormat,
            @JsonProperty("input_type") String inputType,
            Integer dimensions,
            String truncate) {
    }

    record EmbeddingResponse(List<Item> data) {
        record Item(List<Double> embedding) {
        }
    }
}
