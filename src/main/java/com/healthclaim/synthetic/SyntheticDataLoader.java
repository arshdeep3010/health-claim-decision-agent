package com.healthclaim.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.synthetic.model.SyntheticDataset;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the synthetic dataset from the classpath. Shared by the dev seeder and the
 * evaluation harness so both operate on the exact same labeled data.
 */
public final class SyntheticDataLoader {

    public static final String DEFAULT_RESOURCE = "synthetic/dataset.json";

    private SyntheticDataLoader() {
    }

    public static SyntheticDataset load(ObjectMapper objectMapper) {
        return load(objectMapper, DEFAULT_RESOURCE);
    }

    public static SyntheticDataset load(ObjectMapper objectMapper, String resourcePath) {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return objectMapper.readValue(in, SyntheticDataset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load synthetic dataset: " + resourcePath, e);
        }
    }
}
