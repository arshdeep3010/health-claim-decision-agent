package com.healthclaim.decision.service;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.common.web.CorrelationIdFilter;
import com.healthclaim.decision.rules.RuleOutcome;
import com.healthclaim.decision.rules.RuleResult;
import com.healthclaim.decision.stream.AnalysisListener;
import com.healthclaim.decision.stream.AnalysisStage;
import com.healthclaim.decision.stream.StageEvent;
import org.slf4j.MDC;
import com.healthclaim.decision.config.DecisionProperties;
import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.decision.context.ClaimFactsView;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.dto.DecisionResponse;
import com.healthclaim.decision.provider.AiDecision;
import com.healthclaim.decision.provider.DecisionPromptPayload;
import com.healthclaim.decision.provider.DecisionProvider;
import com.healthclaim.decision.rules.RuleEngine;
import com.healthclaim.decision.rules.RuleEvaluation;
import com.healthclaim.rag.evidence.EvidenceResult;
import com.healthclaim.rag.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates claim analysis (§16). Java owns the final verdict; the AI only
 * assists. The pipeline is a sequence of small, testable steps:
 * load -&gt; retrieve evidence -&gt; run rules -&gt; build context -&gt; (maybe) call AI
 * -&gt; apply safety constraints -&gt; persist. RAG or AI failure routes to
 * HUMAN_REVIEW; it never produces an automatic approve/reject.
 */
@Service
public class DecisionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DecisionOrchestrator.class);

    private final DecisionStore store;
    private final RuleEngine ruleEngine;
    private final RetrievalService retrievalService;
    private final DecisionProvider decisionProvider;
    private final DecisionMapper decisionMapper;
    private final DecisionProperties properties;
    private final com.healthclaim.audit.service.AuditService auditService;
    private final com.healthclaim.observability.HealthClaimMetrics metrics;
    private final String promptVersion;

    public DecisionOrchestrator(DecisionStore store,
                                RuleEngine ruleEngine,
                                RetrievalService retrievalService,
                                DecisionProvider decisionProvider,
                                DecisionMapper decisionMapper,
                                DecisionProperties properties,
                                com.healthclaim.audit.service.AuditService auditService,
                                com.healthclaim.observability.HealthClaimMetrics metrics,
                                com.healthclaim.ai.config.MercuryProperties mercuryProperties) {
        this.store = store;
        this.ruleEngine = ruleEngine;
        this.retrievalService = retrievalService;
        this.decisionProvider = decisionProvider;
        this.decisionMapper = decisionMapper;
        this.properties = properties;
        this.auditService = auditService;
        this.metrics = metrics;
        this.promptVersion = mercuryProperties.promptVersion();
    }

    public DecisionResponse analyze(UUID claimId, String idempotencyKey) {
        return analyze(claimId, idempotencyKey, AnalysisListener.NONE);
    }

    /**
     * Same as {@link #analyze(UUID, String)}, additionally reporting each stage to
     * {@code listener} as it starts and finishes (used by the streaming endpoint).
     * The listener only observes; it never influences the decision.
     */
    public DecisionResponse analyze(UUID claimId, String idempotencyKey, AnalysisListener listener) {
        long startNanos = System.nanoTime();
        // Idempotency: reuse a prior decision stored under the same key.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Decision> existing = store.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Decision d = existing.get();
                emit(listener, StageEvent.skipped(AnalysisStage.LOAD, details("reason", "IDEMPOTENT_REPLAY")));
                return decisionMapper.fromEntity(d, store.loadEvidence(d.getId()));
            }
        }

        emit(listener, StageEvent.started(AnalysisStage.LOAD));
        long loadStart = System.nanoTime();
        AnalysisData data;
        try {
            data = store.loadAnalysisData(claimId); // ClaimNotFound if absent
        } catch (RuntimeException e) {
            emit(listener, StageEvent.failed(AnalysisStage.LOAD, millisSince(loadStart), details("reason", "CLAIM_NOT_LOADED")));
            throw e;
        }
        Map<FactKey, ClaimFactsView.FactSnapshot> allFacts = data.facts().all();
        emit(listener, StageEvent.completed(AnalysisStage.LOAD, millisSince(loadStart), details(
                "facts", allFacts.size(),
                "knownFacts", allFacts.values().stream().filter(f -> f.status() == FactStatus.KNOWN).count(),
                "policyNumber", data.policy().policyNumber(),
                "policyStatus", data.policy().status() != null ? data.policy().status().name() : null)));

        // Retrieve policy evidence OUTSIDE any transaction; RAG failure -> flagged.
        boolean ragFailed = false;
        List<EvidenceResult> evidence = List.of();
        String query = buildQuery(data.claim());
        if (!query.isBlank()) {
            emit(listener, StageEvent.started(AnalysisStage.RETRIEVE));
            long ragStart = System.nanoTime();
            boolean ok = false;
            try {
                evidence = retrievalService.retrieve(data.policyId(), query, properties.evidenceTopK());
                ok = true;
            } catch (Exception e) {
                ragFailed = true;
                log.warn("RAG retrieval failed for claim {}: {}", claimId, e.getMessage());
            } finally {
                metrics.recordRagLatency(millisSince(ragStart), ok);
            }
            emit(listener, ragFailed
                    ? StageEvent.failed(AnalysisStage.RETRIEVE, millisSince(ragStart), details("reason", "RAG_FAILURE"))
                    : StageEvent.completed(AnalysisStage.RETRIEVE, millisSince(ragStart), details(
                            "clauses", evidence.size(),
                            "topK", properties.evidenceTopK(),
                            "topSimilarity", evidence.stream().mapToDouble(EvidenceResult::similarity).max().orElse(0.0),
                            "embeddingModel", retrievalService.embeddingModel())));
        } else {
            emit(listener, StageEvent.skipped(AnalysisStage.RETRIEVE, details("reason", "NO_QUERY_TERMS")));
        }

        emit(listener, StageEvent.started(AnalysisStage.RULES));
        long rulesStart = System.nanoTime();
        DecisionInputs inputs = new DecisionInputs(data.claim(), data.policy(), data.facts(), evidence);
        RuleEvaluation rules = ruleEngine.evaluate(inputs);
        emit(listener, StageEvent.completed(AnalysisStage.RULES, millisSince(rulesStart), details(
                "outcome", rules.outcome().name(),
                "evaluated", rules.results().size(),
                "passed", codes(rules, RuleOutcome.ALLOW),
                "flagged", rules.results().stream()
                        .filter(r -> r.outcome() == RuleOutcome.REVIEW || r.outcome() == RuleOutcome.REJECT)
                        .map(RuleResult::code).toList())));
        ClaimDecisionContext context = buildContext(inputs, rules);

        DecisionOutcome outcome = decide(context, rules, ragFailed, evidence, listener);

        List<String> overrides = outcome.reasonCodes().stream().filter(GUARDRAIL_CODES::contains).toList();
        emit(listener, StageEvent.completed(AnalysisStage.GUARDRAILS, 0L, details(
                "decision", outcome.decision().name(),
                "decidedBy", "deterministic".equals(outcome.modelProvider()) ? "RULES" : "AI_WITH_GUARDRAILS",
                "overrides", overrides)));

        emit(listener, StageEvent.started(AnalysisStage.PERSIST));
        long persistStart = System.nanoTime();
        Decision saved = store.saveDecision(claimId, outcome, idempotencyKey);

        metrics.recordDecision(outcome.decision());
        metrics.recordAnalyzeLatency(millisSince(startNanos));
        auditService.recordAnalysis(claimId, outcome.decision(), outcome.modelProvider(), outcome.model(),
                promptVersion, outcome.latencyMs(), rules.outcome().name(), outcome.reasonCodes(),
                evidence.size(), ragFailed);
        emit(listener, StageEvent.completed(AnalysisStage.PERSIST, millisSince(persistStart), details(
                "decisionId", saved.getId(),
                "traceId", MDC.get(CorrelationIdFilter.TRACE_ID))));

        log.info("Claim {} decided: {} (reasons={})", claimId, outcome.decision(), outcome.reasonCodes());
        return decisionMapper.fromOutcome(claimId, saved.getId(), saved.getCreatedAt(), outcome);
    }

    /** Reason codes added by the safety layer rather than by a rule or the model. */
    private static final Set<String> GUARDRAIL_CODES = Set.of(
            "LOW_AI_CONFIDENCE", "AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS", "AI_PROVIDER_FAILURE", "RAG_FAILURE");

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static List<String> codes(RuleEvaluation rules, RuleOutcome outcome) {
        return rules.results().stream().filter(r -> r.outcome() == outcome).map(RuleResult::code).toList();
    }

    /** Ordered key/value details; null values are dropped. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    /** A listener can observe the pipeline but must never break it. */
    private static void emit(AnalysisListener listener, StageEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            log.debug("Analysis listener failed on {} {}: {}", event.stage(), event.status(), e.getMessage());
        }
    }

    private DecisionOutcome decide(ClaimDecisionContext context, RuleEvaluation rules,
                                   boolean ragFailed, List<EvidenceResult> evidence, AnalysisListener listener) {
        switch (rules.outcome()) {
            case REJECT -> {
                emit(listener, StageEvent.skipped(AnalysisStage.AI, details("reason", "RULES_DECIDED")));
                return deterministic(DecisionType.REJECT, rules.drivingReasonCodes(), context, evidence,
                        "Rejected by deterministic policy rules.");
            }
            case REVIEW -> {
                emit(listener, StageEvent.skipped(AnalysisStage.AI, details("reason", "RULES_DECIDED")));
                return deterministic(DecisionType.HUMAN_REVIEW, rules.drivingReasonCodes(), context, evidence,
                        "Routed to human review by deterministic rules.");
            }
            case ALLOW -> {
                if (ragFailed) {
                    emit(listener, StageEvent.skipped(AnalysisStage.AI, details("reason", "RAG_FAILURE")));
                    return deterministic(DecisionType.HUMAN_REVIEW, List.of("RAG_FAILURE"), context, evidence,
                            "Policy evidence retrieval failed; routing to human review.");
                }
                return decideWithAi(context, evidence, listener);
            }
            default -> throw new IllegalStateException("Unknown outcome: " + rules.outcome());
        }
    }

    private DecisionOutcome decideWithAi(ClaimDecisionContext context, List<EvidenceResult> evidence,
                                         AnalysisListener listener) {
        AiDecision ai;
        // Show exactly what the model is given to compare against (same object it receives).
        emit(listener, StageEvent.started(AnalysisStage.AI, details(
                "provider", decisionProvider.providerId(),
                "instructions", DecisionPromptPayload.INSTRUCTIONS,
                "input", DecisionPromptPayload.build(context))));
        long aiStart = System.nanoTime();
        try {
            ai = decisionProvider.decide(context);
            metrics.recordAiCall(decisionProvider.providerId(), "claim-decision", true);
            metrics.recordAiLatency(decisionProvider.providerId(), ai.latencyMs());
            emit(listener, StageEvent.completed(AnalysisStage.AI, millisSince(aiStart), details(
                    "suggestion", ai.decision().name(),
                    "confidence", ai.confidence(),
                    "provider", ai.modelProvider(),
                    "model", ai.model(),
                    "summary", ai.summary(),
                    "reasonCodes", ai.reasonCodes(),
                    "uncertainties", ai.uncertainties())));
        } catch (Exception e) {
            // Any AI/provider/parse failure -> HUMAN_REVIEW (never auto approve/reject).
            metrics.recordAiCall(decisionProvider.providerId(), "claim-decision", false);
            log.warn("AI decision failed; routing to human review: {}", e.getMessage());
            emit(listener, StageEvent.failed(AnalysisStage.AI, millisSince(aiStart), details(
                    "reason", "AI_PROVIDER_FAILURE", "provider", decisionProvider.providerId())));
            List<String> reasons = new ArrayList<>(context.ruleResults().stream().map(r -> r.code()).toList());
            reasons.add("AI_PROVIDER_FAILURE");
            return new DecisionOutcome(DecisionType.HUMAN_REVIEW, null, reasons,
                    context.missingInformation(), context.uncertainties(),
                    "AI provider failed; human review required.",
                    decisionProvider.providerId(), "unavailable", promptVersion, 0L, evidence);
        }

        List<String> reasons = new ArrayList<>(ai.reasonCodes());
        List<String> uncertainties = new ArrayList<>(context.uncertainties());
        uncertainties.addAll(ai.uncertainties());

        DecisionType finalDecision;
        if (ai.decision() == DecisionType.APPROVE) {
            if (ai.confidence() != null && ai.confidence() < properties.minConfidence()) {
                finalDecision = DecisionType.HUMAN_REVIEW;
                reasons.add("LOW_AI_CONFIDENCE");
            } else {
                finalDecision = DecisionType.APPROVE;
            }
        } else if (ai.decision() == DecisionType.REJECT) {
            // AI reject with no deterministic basis -> conservative human review.
            finalDecision = DecisionType.HUMAN_REVIEW;
            reasons.add("AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS");
        } else {
            finalDecision = DecisionType.HUMAN_REVIEW;
        }

        return new DecisionOutcome(finalDecision, ai.confidence(), reasons,
                context.missingInformation(), uncertainties, ai.summary(),
                ai.modelProvider(), ai.model(), promptVersion, ai.latencyMs(), evidence);
    }

    private DecisionOutcome deterministic(DecisionType decision, List<String> reasonCodes,
                                          ClaimDecisionContext context, List<EvidenceResult> evidence,
                                          String summary) {
        return new DecisionOutcome(decision, null, reasonCodes,
                context.missingInformation(), context.uncertainties(), summary,
                "deterministic", "rule-engine", promptVersion, 0L, evidence);
    }

    private ClaimDecisionContext buildContext(DecisionInputs inputs, RuleEvaluation rules) {
        ClaimFactsView facts = inputs.facts();
        List<ClaimDecisionContext.FactEntry> entries = facts.all().entrySet().stream()
                .map(e -> new ClaimDecisionContext.FactEntry(
                        e.getKey().wire(), e.getValue().value(), e.getValue().status().name()))
                .toList();

        List<String> uncertainties = new ArrayList<>();
        if (facts.anyConflicting()) {
            uncertainties.add("Conflicting facts: " + facts.conflictingKeys().stream()
                    .map(FactKey::wire).toList());
        }
        if (facts.knownValue(FactKey.PRE_EXISTING_CONDITION).isEmpty()) {
            uncertainties.add("Pre-existing condition status is uncertain.");
        }

        List<String> missing = new ArrayList<>();
        if (facts.knownValue(FactKey.DIAGNOSIS).isEmpty()) {
            missing.add("diagnosis");
        }
        if (facts.knownValue(FactKey.PROCEDURE).isEmpty()) {
            missing.add("procedure");
        }

        return new ClaimDecisionContext(inputs.claim(), inputs.policy(), entries, inputs.evidence(),
                rules.results(), rules.outcome(), uncertainties, missing);
    }

    private static String buildQuery(ClaimSummary claim) {
        StringBuilder sb = new StringBuilder();
        append(sb, claim.diagnosis());
        append(sb, claim.procedureName());
        append(sb, claim.treatment());
        return sb.toString().strip();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            sb.append(s).append(' ');
        }
    }
}
