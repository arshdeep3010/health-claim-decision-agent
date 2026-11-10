package com.healthclaim.decision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.decision.context.PolicyFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Parses a policy's JSON-text rule fields into typed {@link PolicyFacts}. Parsing
 * is lenient: malformed JSON yields empty structures rather than failing analysis.
 */
@Component
public class PolicyFactsLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyFactsLoader.class);

    private final ObjectMapper objectMapper;

    public PolicyFactsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PolicyFacts load(Policy policy) {
        return new PolicyFacts(
                policy.getPolicyNumber(),
                policy.getStatus(),
                policy.getEffectiveDate(),
                policy.getExpiryDate(),
                policy.getCoverageLimit(),
                policy.getDeductible(),
                policy.getCopayPercentage(),
                policy.getCurrency(),
                parseExclusions(policy.getExclusions()),
                parseWaitingPeriods(policy.getWaitingPeriods()));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseExclusions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Could not parse policy exclusions JSON; treating as none: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseWaitingPeriods(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Could not parse policy waiting periods JSON; treating as none: {}", e.getMessage());
            return Map.of();
        }
    }
}
