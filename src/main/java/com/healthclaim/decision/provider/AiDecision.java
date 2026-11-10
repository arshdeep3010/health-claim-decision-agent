package com.healthclaim.decision.provider;

import com.healthclaim.decision.domain.DecisionType;

import java.util.List;

/**
 * The AI layer's semantic judgment. {@code confidence} is preserved only when the
 * model actually returns a value in [0,1]; it is never fabricated.
 */
public record AiDecision(
        DecisionType decision,
        Double confidence,
        List<String> reasonCodes,
        String summary,
        List<String> uncertainties,
        String modelProvider,
        String model,
        long latencyMs,
        int retryCount
) {
}
