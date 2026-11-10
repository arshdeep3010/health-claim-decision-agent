package com.healthclaim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retrieval (RAG) configuration.
 */
@ConfigurationProperties("app.rag")
public record RagProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("500") int maxSnippetLength
) {
}
