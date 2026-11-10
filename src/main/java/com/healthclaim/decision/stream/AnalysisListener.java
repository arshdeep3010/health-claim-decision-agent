package com.healthclaim.decision.stream;

/**
 * Receives stage progress while a claim is analyzed. Implementations must be
 * cheap and must not throw; the orchestrator also guards every call so a broken
 * listener can never change a decision.
 */
@FunctionalInterface
public interface AnalysisListener {

    AnalysisListener NONE = event -> { };

    void onEvent(StageEvent event);
}
