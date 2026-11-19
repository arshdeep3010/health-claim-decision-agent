package com.healthclaim.rag.embedding;

import com.healthclaim.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the hosted embedding client with HTTP timeouts. Done here rather than in the
 * provider's constructor so unit tests can bind a mock server to the builder.
 * Active only when {@code app.embedding.provider=openai-compatible}, so dev/CI (local
 * provider) never requires network or keys.
 */
@Configuration
class EmbeddingClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.embedding", name = "provider", havingValue = "openai-compatible")
    OpenAiCompatibleEmbeddingProvider openAiCompatibleEmbeddingProvider(EmbeddingProperties properties,
                                                                         RestClient.Builder builder) {
        return new OpenAiCompatibleEmbeddingProvider(properties, OpenAiCompatibleEmbeddingProvider.withTimeouts(builder));
    }
}
