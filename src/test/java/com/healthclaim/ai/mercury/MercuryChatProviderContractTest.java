package com.healthclaim.ai.mercury;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.config.MercuryProperties;
import com.healthclaim.ai.exception.AiProviderException;
import com.healthclaim.ai.llm.ChatMessage;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.ai.llm.JsonSchemaSpec;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MercuryChatProviderContractTest {

    private MockWebServer server;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesContentModelAndUsage() throws Exception {
        server.enqueue(json("""
                {"id":"x","model":"mercury-2.5",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Approved"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""));

        ChatResponse response = provider(props(2, 5), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test"));

        assertThat(response.content()).isEqualTo("Approved");
        assertThat(response.model()).isEqualTo("mercury-2.5");
        assertThat(response.promptTokens()).isEqualTo(11);
        assertThat(response.completionTokens()).isEqualTo(7);
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.retryCount()).isZero();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void sendsJsonSchemaResponseFormat() throws Exception {
        server.enqueue(json("""
                {"model":"m","choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}"""));

        JsonSchemaSpec schema = new JsonSchemaSpec("ClaimFacts",
                "{\"type\":\"object\",\"properties\":{\"diagnosis\":{\"type\":\"string\"}}}", true);
        provider(props(2, 5), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("extract")), "unit-test").withSchema(schema));

        String body = server.takeRequest().getBody().readUtf8();
        var node = om.readTree(body);
        assertThat(node.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(node.path("response_format").path("json_schema").path("name").asText()).isEqualTo("ClaimFacts");
        assertThat(node.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(node.path("response_format").path("json_schema").path("schema").path("type").asText())
                .isEqualTo("object");
    }

    @Test
    void retriesOnServerErrorThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("""
                {"model":"m","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""));

        ChatResponse response = provider(props(2, 5), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test"));

        assertThat(response.content()).isEqualTo("ok");
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotRetryOn4xx() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        assertThatThrownBy(() -> provider(props(2, 5), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test")))
                .isInstanceOf(AiProviderException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void failsAfterExhaustingRetriesOnPersistent5xx() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> provider(props(2, 5), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test")))
                .isInstanceOf(AiProviderException.class);

        assertThat(server.getRequestCount()).isEqualTo(3); // 1 + 2 retries
    }

    @Test
    void readTimeoutIsTreatedAsFailure() {
        server.enqueue(json("""
                {"model":"m","choices":[{"message":{"content":"slow"}}]}""").setBodyDelay(2, TimeUnit.SECONDS));

        // timeoutMs=300, maxRetries=0 -> single attempt that times out
        assertThatThrownBy(() -> provider(propsWithTimeout(0, 300), defaultBreaker())
                .chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test")))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    void circuitBreakerOpensAndFailsFast() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        CircuitBreaker breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build()).circuitBreaker("mercury");

        var p = provider(props(0, 300), breaker); // no retries; each call = 1 failure
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> p.chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test")))
                    .isInstanceOf(AiProviderException.class);
        }
        // Circuit now OPEN: third call must fail fast with NO new HTTP request.
        assertThatThrownBy(() -> p.chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "unit-test")))
                .isInstanceOf(AiProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // --- helpers ---

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(body);
    }

    private MercuryProperties props(int maxRetries, long backoffMs) {
        return new MercuryProperties("openrouter", server.url("/v1").toString(), "test-key",
                "mercury-2.5", 5000, maxRetries, backoffMs, "v1");
    }

    private MercuryProperties propsWithTimeout(int maxRetries, int timeoutMs) {
        return new MercuryProperties("openrouter", server.url("/v1").toString(), "test-key",
                "mercury-2.5", timeoutMs, maxRetries, 5, "v1");
    }

    private CircuitBreaker defaultBreaker() {
        return CircuitBreakerRegistry.ofDefaults().circuitBreaker("mercury");
    }

    private MercuryChatProvider provider(MercuryProperties props, CircuitBreaker breaker) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory);
        if (StringUtils.hasText(props.apiKey())) {
            builder.defaultHeader("Authorization", "Bearer " + props.apiKey());
        }
        return new MercuryChatProvider(props, om, breaker, builder.build());
    }
}
