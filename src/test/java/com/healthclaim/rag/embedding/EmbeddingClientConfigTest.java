package com.healthclaim.rag.embedding;

import com.healthclaim.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** The hosted embedding client is created only for {@code provider=openai-compatible}. */
class EmbeddingClientConfigTest {

    @Configuration
    @EnableConfigurationProperties(EmbeddingProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingClientConfig.class, Props.class)
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    void hostedProviderIsCreatedForOpenAiCompatible() {
        runner.withPropertyValues("app.embedding.provider=openai-compatible",
                        "app.embedding.model=nvidia/nemotron-3.5-embed-1b",
                        "app.embedding.base-url=https://integrate.api.nvidia.com/v1")
                .run(ctx -> assertThat(ctx).hasSingleBean(EmbeddingProvider.class)
                        .getBean(EmbeddingProvider.class).isInstanceOf(OpenAiCompatibleEmbeddingProvider.class));
    }

    @Test
    void noHostedProviderForLocal() {
        runner.withPropertyValues("app.embedding.provider=local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OpenAiCompatibleEmbeddingProvider.class));
    }
}
