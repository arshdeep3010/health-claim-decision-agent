package com.healthclaim.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.NoopChatLlmProvider;
import com.healthclaim.ai.mercury.MercuryChatProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Chooses the active {@link ChatLlmProvider} at runtime from configuration:
 * a real {@link MercuryChatProvider} when an OpenAI-compatible provider is set,
 * otherwise the {@link NoopChatLlmProvider} (so dev/CI need no keys).
 */
@Configuration
public class AiProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(AiProviderConfig.class);

    @Bean
    public ChatLlmProvider chatLlmProvider(MercuryProperties props,
                                           ObjectMapper objectMapper,
                                           CircuitBreakerRegistry circuitBreakerRegistry) {
        if (!props.isOpenAiCompatible()) {
            log.info("No AI chat provider configured (app.mercury.provider='{}'); using no-op provider.",
                    props.provider());
            return new NoopChatLlmProvider();
        }
        if (!StringUtils.hasText(props.baseUrl()) || !StringUtils.hasText(props.model())) {
            throw new IllegalStateException(
                    "MERCURY_BASE_URL and MERCURY_MODEL are required when MERCURY_PROVIDER=" + props.provider());
        }
        log.info("Configuring Mercury chat provider '{}' (model='{}', timeoutMs={}, maxRetries={})",
                props.provider(), props.model(), props.timeoutMs(), props.maxRetries());

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("mercury");
        return new MercuryChatProvider(props, objectMapper, circuitBreaker, restClient(props));
    }

    private RestClient restClient(MercuryProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory);
        if (StringUtils.hasText(props.apiKey())) {
            builder.defaultHeader("Authorization", "Bearer " + props.apiKey());
        }
        return builder.build();
    }
}
