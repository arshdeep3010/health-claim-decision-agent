package com.healthclaim.decision.provider;

import com.healthclaim.decision.domain.DecisionType;

import java.util.List;
import java.util.Map;

/**
 * The AI layer's semantic judgment. {@code confidence} is preserved only when the
 * model actually returns a value in [0,1]; it is never fabricated.
 *
 * <p>{@code extras} carries provider-specific detail for display only (for example a
 * decision model's per-outcome probabilities and the sources it consulted). It never
 * influences the guardrails.
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
        int retryCount,
        Map<String, Object> extras
) {

    public AiDecision {
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    public AiDecision(DecisionType decision, Double confidence, List<String> reasonCodes, String summary,
                      List<String> uncertainties, String modelProvider, String model, long latencyMs,
                      int retryCount) {
        this(decision, confidence, reasonCodes, summary, uncertainties, modelProvider, model, latencyMs,
                retryCount, Map.of());
    }
}
