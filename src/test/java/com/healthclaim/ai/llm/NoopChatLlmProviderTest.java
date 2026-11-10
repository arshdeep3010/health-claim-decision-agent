package com.healthclaim.ai.llm;

import com.healthclaim.ai.exception.AiProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoopChatLlmProviderTest {

    @Test
    void failsClearlyAndReportsUnconfigured() {
        NoopChatLlmProvider provider = new NoopChatLlmProvider();

        assertThat(provider.isConfigured()).isFalse();
        assertThat(provider.providerId()).isEqualTo("noop");
        assertThatThrownBy(() -> provider.chat(ChatRequest.of(List.of(ChatMessage.user("hi")), "t")))
                .isInstanceOf(AiProviderException.class);
    }
}
