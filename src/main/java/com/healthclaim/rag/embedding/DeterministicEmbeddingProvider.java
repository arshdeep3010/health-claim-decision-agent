package com.healthclaim.rag.embedding;

import com.healthclaim.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Dependency-free, deterministic embedding used as the default for dev and CI so
 * the pipeline runs with zero API keys and offline. It hashes tokens into buckets
 * and L2-normalises, giving lexical-overlap similarity — enough to exercise and
 * test retrieval, but NOT a substitute for a real semantic model in production.
 */
@Component
@ConditionalOnProperty(prefix = "app.embedding", name = "provider", havingValue = "local", matchIfMissing = true)
public class DeterministicEmbeddingProvider implements EmbeddingProvider {

    private final int dim;

    public DeterministicEmbeddingProvider(EmbeddingProperties properties) {
        this.dim = properties.dim();
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dim];
        if (text != null) {
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int hash = token.hashCode();
                vector[Math.floorMod(hash, dim)] += 1.0f;
                vector[Math.floorMod(hash * 31 + 7, dim)] += ((hash & 1) == 0) ? 0.5f : -0.5f;
            }
        }
        normalize(vector);
        return vector;
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public String modelName() {
        return "local-deterministic-v1";
    }

    private static void normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float value : vector) {
            sumSquares += (double) value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}
