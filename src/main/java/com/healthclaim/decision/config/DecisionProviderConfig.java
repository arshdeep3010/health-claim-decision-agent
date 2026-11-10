package com.healthclaim.decision.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.ai.config.MercuryProperties;
import com.healthclaim.ai.llm.ChatLlmProvider;
import com.healthclaim.decision.provider.DecisionProvider;
import com.healthclaim.decision.provider.MercuryDecisionProvider;
import com.healthclaim.decision.provider.ParallelDecisionProvider;
import com.healthclaim.decision.provider.RuleOnlyDecisionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Selects the {@link DecisionProvider}: a decision model when
 * {@code DECISION_ENGINE=decision-model} and a key is set, otherwise a chat LLM when
 * an OpenAI-compatible provider is configured, otherwise the deterministic rule-only
 * baseline (so dev/CI work offline with no keys).
 */
@Configuration
public class DecisionProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(DecisionProviderConfig.class);

    @Bean
    public DecisionProvider decisionProvider(MercuryProperties mercuryProperties,
                                             DecisionModelProperties decisionModelProperties,
                                             ChatLlmProvider chatLlmProvider,
                                             ObjectMapper objectMapper,
                                             RestClient.Builder restClientBuilder) {
        if (decisionModelProperties.useDecisionModel()) {
            log.info("Using decision model '{}' via the OpenRouter Decisions API.", decisionModelProperties.model());
            return new ParallelDecisionProvider(decisionModelProperties,
                    ParallelDecisionProvider.withTimeouts(restClientBuilder, decisionModelProperties));
        }
        if (mercuryProperties.isOpenAiCompatible()) {
            log.info("Using Mercury semantic decision provider '{}'.", mercuryProperties.provider());
            return new MercuryDecisionProvider(chatLlmProvider, objectMapper, mercuryProperties);
        }
        log.info("Using rule-only decision provider (no AI chat provider configured).");
        return new RuleOnlyDecisionProvider();
    }
}
