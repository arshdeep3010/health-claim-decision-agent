package com.healthclaim.rag.embedding;

import java.util.List;

/**
 * Produces vector embeddings for text. Implementations are selected by
 * configuration ({@code app.embedding.provider}) so the RAG layer never depends
 * on a specific embedding vendor.
 */
public interface EmbeddingProvider {

    /** Embeds a single text. */
    float[] embed(String text);

    /** Embeds a batch; default loops, real providers override with one call. */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * Embeds a search query. Asymmetric retrieval models embed queries and
     * documents differently; symmetric providers just use {@link #embed}.
     */
    default float[] embedQuery(String query) {
        return embed(query);
    }

    /** Embeds document passages for indexing (see {@link #embedQuery}). */
    default List<float[]> embedPassages(List<String> passages) {
        return embedBatch(passages);
    }

    /** The fixed dimension of vectors this provider produces. */
    int dimension();

    /** Identifier of the underlying model (stored for audit). */
    String modelName();
}
