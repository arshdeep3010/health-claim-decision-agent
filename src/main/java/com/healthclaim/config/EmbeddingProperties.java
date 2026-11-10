package com.healthclaim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding provider configuration. {@code provider} selects the active
 * {@code EmbeddingProvider} ("local" deterministic fallback for dev/CI, or
 * "openai-compatible" for a hosted endpoint). {@code dim} MUST match both the
 * model's output size and the migrated pgvector column dimension.
 *
 * <p>The optional fields cover hosted retrieval models that go beyond the plain
 * OpenAI request (e.g. NVIDIA NIM):
 * <ul>
 *   <li>{@code queryInputType} / {@code passageInputType}: sent as {@code input_type}
 *       for asymmetric models, which embed search queries and indexed documents
 *       differently (NVIDIA: "query" / "passage"). Left blank, the field is omitted.</li>
 *   <li>{@code requestDimensions}: send {@code dimensions = dim}, for models that can
 *       shorten their output (Matryoshka), so a large model still fits the column.</li>
 *   <li>{@code truncate}: sent as {@code truncate} (NVIDIA: "END") so an over-long
 *       input is cut instead of rejected. Left blank, the field is omitted.</li>
 *   <li>{@code batchSize}: the most inputs sent in one request.</li>
 * </ul>
 */
@ConfigurationProperties("app.embedding")
public record EmbeddingProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("") String model,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("384") int dim,
        @DefaultValue("") String queryInputType,
        @DefaultValue("") String passageInputType,
        @DefaultValue("false") boolean requestDimensions,
        @DefaultValue("") String truncate,
        @DefaultValue("32") int batchSize
) {

}
