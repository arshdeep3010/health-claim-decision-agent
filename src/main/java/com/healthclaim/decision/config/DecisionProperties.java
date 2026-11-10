package com.healthclaim.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Decision-layer tuning.
 *
 * @param minConfidence   when the AI returns a confidence below this, the final
 *                        decision is forced to HUMAN_REVIEW (applied only when a
 *                        confidence is actually present; never fabricated).
 * @param evidenceTopK    number of policy chunks to retrieve as decision evidence.
 */
@ConfigurationProperties("app.decision")
public record DecisionProperties(
        @DefaultValue("0.5") double minConfidence,
        @DefaultValue("5") int evidenceTopK
) {
}
