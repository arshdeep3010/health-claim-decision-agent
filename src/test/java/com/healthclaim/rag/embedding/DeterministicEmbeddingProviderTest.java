package com.healthclaim.rag.embedding;

import com.healthclaim.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DeterministicEmbeddingProviderTest {

    private final DeterministicEmbeddingProvider provider =
            new DeterministicEmbeddingProvider(new EmbeddingProperties("local", "", "", "", 384, "", "", false, "", 32));

    @Test
    void isDeterministicAndCorrectlySized() {
        float[] a = provider.embed("laparoscopic cholecystectomy for gallstones");
        float[] b = provider.embed("laparoscopic cholecystectomy for gallstones");

        assertThat(a).hasSize(384);
        assertThat(a).containsExactly(b, within(0.0f));
    }

    @Test
    void isL2Normalised() {
        float[] v = provider.embed("inpatient hospitalization coverage");
        double sumSquares = 0;
        for (float x : v) {
            sumSquares += (double) x * x;
        }
        assertThat(Math.sqrt(sumSquares)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void overlappingTextIsMoreSimilarThanUnrelated() {
        float[] query = provider.embed("cosmetic surgery is excluded");
        float[] related = provider.embed("cosmetic surgery and experimental treatments are excluded");
        float[] unrelated = provider.embed("inpatient hospitalization and ambulance charges are covered");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot; // both already L2-normalised
    }
}
