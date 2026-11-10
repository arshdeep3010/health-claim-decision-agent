package com.healthclaim.rag.embedding;

import com.healthclaim.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleEmbeddingProviderTest {

    private static final String URL = "https://embed.example.test/v1";

    private MockRestServiceServer server;

    private OpenAiCompatibleEmbeddingProvider provider(EmbeddingProperties props) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(props, builder);
        provider.setBackOffMillis(1);
        return provider;
    }

    private static EmbeddingProperties nvidiaStyle(int batchSize) {
        return new EmbeddingProperties("openai-compatible", "nvidia/nemotron-3.5-embed-1b", URL, "test-key", 4,
                "query", "passage", true, "END", batchSize);
    }

    private static String vectors(int count, int dim) {
        String vector = Collections.nCopies(dim, "0.5").stream().collect(Collectors.joining(",", "[", "]"));
        return Collections.nCopies(count, "{\"embedding\":" + vector + "}").stream()
                .collect(Collectors.joining(",", "{\"data\":[", "]}"));
    }

    @Test
    void queryCarriesQueryInputTypeDimensionsAndTruncate() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(32));
        server.expect(requestTo(URL + "/embeddings"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("nvidia/nemotron-3.5-embed-1b"))
                .andExpect(jsonPath("$.input[0]").value("knee replacement"))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andExpect(jsonPath("$.dimensions").value(4))
                .andExpect(jsonPath("$.truncate").value("END"))
                .andExpect(jsonPath("$.encoding_format").value("float"))
                .andRespond(withSuccess(vectors(1, 4), MediaType.APPLICATION_JSON));

        assertThat(provider.embedQuery("knee replacement")).hasSize(4);
        server.verify();
    }

    @Test
    void passagesUsePassageInputTypeAndAreSentInBatches() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(2));
        server.expect(jsonPath("$.input_type").value("passage"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(vectors(2, 4), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.input_type").value("passage"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(vectors(1, 4), MediaType.APPLICATION_JSON));

        assertThat(provider.embedPassages(List.of("a", "b", "c"))).hasSize(3);
        server.verify();
    }

    @Test
    void plainOpenAiConfigOmitsTheOptionalFields() {
        OpenAiCompatibleEmbeddingProvider provider = provider(
                new EmbeddingProperties("openai-compatible", "text-embedding-3-small", URL, "", 4,
                        "", "", false, "", 32));
        server.expect(jsonPath("$.input_type").doesNotExist())
                .andExpect(jsonPath("$.dimensions").doesNotExist())
                .andExpect(jsonPath("$.truncate").doesNotExist())
                .andRespond(withSuccess(vectors(1, 4), MediaType.APPLICATION_JSON));

        assertThat(provider.embedQuery("x")).hasSize(4);
        server.verify();
    }

    @Test
    void wrongDimensionFailsFast() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(32));
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withSuccess(vectors(1, 3), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.embedQuery("x"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("dimension 3");
    }

    @Test
    void transientServerErrorIsRetried() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(32));
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"service inference failed\"}"));
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withSuccess(vectors(1, 4), MediaType.APPLICATION_JSON));

        assertThat(provider.embedPassages(List.of("x"))).hasSize(1);
        server.verify();
    }

    @Test
    void persistentServerErrorGivesUpAfterMaxAttempts() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(32));
        for (int i = 0; i < OpenAiCompatibleEmbeddingProvider.MAX_ATTEMPTS; i++) {
            server.expect(requestTo(URL + "/embeddings"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        assertThatThrownBy(() -> provider.embedQuery("x"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 500");
        server.verify();
    }

    @Test
    void providerErrorBodyIsSurfaced() {
        OpenAiCompatibleEmbeddingProvider provider = provider(nvidiaStyle(32));
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"'input_type' parameter is required for asymmetric models\"}"));

        assertThatThrownBy(() -> provider.embedPassages(List.of("x")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("input_type");
    }
}
