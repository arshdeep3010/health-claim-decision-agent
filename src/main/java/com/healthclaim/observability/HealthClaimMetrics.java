package com.healthclaim.observability;

import com.healthclaim.decision.domain.DecisionType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Application metrics (§38): decision distribution and the latencies of the
 * expensive stages (analyze, RAG, AI). Exposed via {@code /actuator/prometheus}.
 */
@Component
public class HealthClaimMetrics {

    private final MeterRegistry registry;

    public HealthClaimMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDecision(DecisionType decision) {
        registry.counter("healthclaim.decisions", "decision", decision.name()).increment();
    }

    public void recordAnalyzeLatency(long ms) {
        registry.timer("healthclaim.analyze.latency").record(Duration.ofMillis(ms));
    }

    public void recordRagLatency(long ms, boolean success) {
        registry.timer("healthclaim.rag.latency", "outcome", success ? "success" : "failure")
                .record(Duration.ofMillis(ms));
    }

    public void recordAiCall(String provider, String type, boolean success) {
        registry.counter("healthclaim.ai.calls",
                "provider", provider == null ? "unknown" : provider,
                "type", type,
                "outcome", success ? "success" : "failure").increment();
    }

    public void recordAiLatency(String provider, long ms) {
        registry.timer("healthclaim.ai.latency", "provider", provider == null ? "unknown" : provider)
                .record(Duration.ofMillis(ms));
    }
}
