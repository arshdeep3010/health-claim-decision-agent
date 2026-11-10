package com.healthclaim.decision.stream;

/**
 * The observable stages of a claim analysis, in pipeline order. {@code AUTH} is
 * reported by the web layer (the request already passed the security filter chain);
 * the rest are reported by {@link com.healthclaim.decision.service.DecisionOrchestrator}.
 */
public enum AnalysisStage {
    AUTH,
    LOAD,
    RETRIEVE,
    RULES,
    AI,
    GUARDRAILS,
    PERSIST
}
