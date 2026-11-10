package com.healthclaim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chunking configuration for policy document ingestion. Sizes are in characters.
 */
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(
        @DefaultValue("1000") int chunkSize,
        @DefaultValue("150") int chunkOverlap,
        @DefaultValue("200") int minChunkSize
) {
}
