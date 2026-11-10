package com.healthclaim.claim.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.ai.llm.ChatRequest;
import com.healthclaim.ai.llm.ChatResponse;
import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.exception.ClaimExtractionException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaimFactExtractor}'s defensive validation/normalization
 * of the model's output (no container, mocked chat provider).
 */
class ClaimFactExtractorTest {

    private final ObjectMapper om = new ObjectMapper();

    private ClaimFactExtractor extractor(String content) {
        ChatLlmProvider chat = Mockito.mock(ChatLlmProvider.class);
        when(chat.providerId()).thenReturn("mock");
        when(chat.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(content, "mock-model", 1, 1, "stop", 1, 0));
        return new ClaimFactExtractor(chat, om);
    }

    private static ClaimExtractionInput input() {
        return new ClaimExtractionInput("Hospital", "Gallstones", "Laparoscopic Cholecystectomy",
                "inpatient", LocalDate.now().minusDays(3), LocalDate.now().minusDays(1),
                new BigDecimal("145000"), "INR");
    }

    private Map<FactKey, ExtractedFact> byKey(List<ExtractedFact> facts) {
        return facts.stream().collect(Collectors.toMap(ExtractedFact::key, f -> f));
    }

    @Test
    void coercesNumbersAndBooleansAndPreservesConflicting() {
        var facts = byKey(extractor("""
                {"facts":[
                 {"key":"diagnosis","value":"Gallstones","status":"KNOWN","confidence":0.9},
                 {"key":"claimAmount","value":"INR 145,000","status":"KNOWN","confidence":null},
                 {"key":"preExistingCondition","value":"yes","status":"KNOWN","confidence":null},
                 {"key":"procedure","value":"Chole","status":"CONFLICTING","confidence":null}
                ]}""").extract(input()));

        assertThat(facts.get(FactKey.CLAIM_AMOUNT).status()).isEqualTo(FactStatus.KNOWN);
        assertThat(facts.get(FactKey.CLAIM_AMOUNT).value()).isEqualTo("145000"); // commas + currency stripped
        assertThat(facts.get(FactKey.PRE_EXISTING_CONDITION).value()).isEqualTo("true"); // yes -> true
        assertThat(facts.get(FactKey.PROCEDURE).status()).isEqualTo(FactStatus.CONFLICTING);
        assertThat(facts.get(FactKey.DIAGNOSIS).confidence()).isEqualTo(0.9);
    }

    @Test
    void downgradesUnparseableNumberAndFillsMissingKeys() {
        var facts = byKey(extractor("""
                {"facts":[
                 {"key":"claimAmount","value":"a lot","status":"KNOWN","confidence":null}
                ]}""").extract(input()));

        // Unparseable number KNOWN -> UNKNOWN
        assertThat(facts.get(FactKey.CLAIM_AMOUNT).status()).isEqualTo(FactStatus.UNKNOWN);
        // Every canonical key is present; omitted ones default to UNKNOWN
        assertThat(facts).containsKeys(FactKey.values());
        assertThat(facts.get(FactKey.DIAGNOSIS).status()).isEqualTo(FactStatus.UNKNOWN);
    }

    @Test
    void ignoresUnknownKeys() {
        var facts = byKey(extractor("""
                {"facts":[
                 {"key":"bloodType","value":"O+","status":"KNOWN","confidence":null},
                 {"key":"diagnosis","value":"Gallstones","status":"KNOWN","confidence":null}
                ]}""").extract(input()));

        assertThat(facts).hasSize(FactKey.values().length); // unknown key dropped, canonical set intact
        assertThat(facts.get(FactKey.DIAGNOSIS).value()).isEqualTo("Gallstones");
    }

    @Test
    void malformedOutputThrows() {
        assertThatThrownBy(() -> extractor("totally not json").extract(input()))
                .isInstanceOf(ClaimExtractionException.class);
        assertThatThrownBy(() -> extractor("{\"notfacts\":1}").extract(input()))
                .isInstanceOf(ClaimExtractionException.class);
    }
}
