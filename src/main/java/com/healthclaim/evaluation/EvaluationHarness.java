package com.healthclaim.evaluation;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.decision.context.ClaimFactsView;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.rules.DeterministicOutcome;
import com.healthclaim.decision.rules.RuleEngine;
import com.healthclaim.decision.rules.impl.ConflictingFactsRule;
import com.healthclaim.decision.rules.impl.CoverageLimitRule;
import com.healthclaim.decision.rules.impl.ExclusionRule;
import com.healthclaim.decision.rules.impl.MissingInformationRule;
import com.healthclaim.decision.rules.impl.PolicyActiveRule;
import com.healthclaim.decision.rules.impl.PreExistingConditionRule;
import com.healthclaim.decision.rules.impl.WaitingPeriodRule;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.synthetic.model.SyntheticDataset;
import com.healthclaim.synthetic.model.SyntheticDataset.SyntheticClaim;
import com.healthclaim.synthetic.model.SyntheticDataset.SyntheticPolicy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Evaluation harness (§45). Runs a decision "arm" over the labeled synthetic
 * dataset and computes real metrics. The RULE_ONLY arm is fully offline and
 * deterministic (it reproduces the rule-grounded labels, serving as a consistency
 * baseline); LLM/Hybrid arms can be plugged in by supplying a predictor that calls
 * a configured provider. No results are fabricated.
 */
public final class EvaluationHarness {

    /** A decision arm: maps a (claim, policy) to a predicted decision. */
    public interface Predictor extends BiFunction<SyntheticClaim, SyntheticPolicy, DecisionType> {
    }

    public record Report(EvaluationMetrics metrics, List<EvaluationResult> results) {
    }

    private EvaluationHarness() {
    }

    public static Report evaluate(SyntheticDataset dataset, String arm, Predictor predictor) {
        Map<String, SyntheticPolicy> policies = dataset.policies().stream()
                .collect(Collectors.toMap(SyntheticPolicy::policyNumber, p -> p));

        List<EvaluationResult> results = new ArrayList<>();
        long start = System.nanoTime();
        for (SyntheticClaim claim : dataset.claims()) {
            SyntheticPolicy policy = policies.get(claim.policyNumber());
            DecisionType predicted = predictor.apply(claim, policy);
            DecisionType expected = DecisionType.valueOf(claim.expectedDecision());
            results.add(new EvaluationResult(claim.claimNumber(), claim.scenario(), expected, predicted));
        }
        double avgLatencyMs = results.isEmpty() ? 0.0
                : (System.nanoTime() - start) / 1_000_000.0 / results.size();

        // RULE_ONLY makes no AI calls -> zero marginal cost.
        double cost = "RULE_ONLY".equals(arm) ? 0.0 : Double.NaN;
        EvaluationMetrics metrics = EvaluationMetrics.compute(arm, results, avgLatencyMs, cost);
        return new Report(metrics, results);
    }

    /** The deterministic rule-engine arm (offline). */
    public static Predictor ruleOnlyPredictor() {
        RuleEngine engine = new RuleEngine(List.of(
                new PolicyActiveRule(), new ExclusionRule(), new CoverageLimitRule(),
                new WaitingPeriodRule(), new MissingInformationRule(),
                new ConflictingFactsRule(), new PreExistingConditionRule()));
        return (claim, policy) -> {
            DeterministicOutcome outcome = engine.evaluate(toInputs(claim, policy)).outcome();
            return switch (outcome) {
                case REJECT -> DecisionType.REJECT;
                case REVIEW -> DecisionType.HUMAN_REVIEW;
                case ALLOW -> DecisionType.APPROVE;
            };
        };
    }

    private static DecisionInputs toInputs(SyntheticClaim c, SyntheticPolicy p) {
        ClaimSummary claim = new ClaimSummary(null, c.claimNumber(), c.provider(), c.diagnosis(),
                c.procedureName(), c.treatment(), c.hospitalizationStart(), c.hospitalizationEnd(),
                c.claimAmount(), c.currency());
        PolicyFacts policy = new PolicyFacts(p.policyNumber(), PolicyStatus.valueOf(p.status()),
                p.effectiveDate(), p.expiryDate(), p.coverageLimit(), p.deductible(), p.copayPercentage(),
                p.currency(), p.exclusions() != null ? p.exclusions() : List.of(),
                p.waitingPeriods() != null ? p.waitingPeriods() : Map.of());

        Map<FactKey, ClaimFactsView.FactSnapshot> facts = new EnumMap<>(FactKey.class);
        if (c.facts() != null) {
            c.facts().forEach((key, fv) -> FactKey.fromWire(key).ifPresent(fk ->
                    facts.put(fk, new ClaimFactsView.FactSnapshot(fv.value(), parse(fv.status()), null))));
        }
        return new DecisionInputs(claim, policy, new ClaimFactsView(facts), List.of());
    }

    private static FactStatus parse(String status) {
        try {
            return FactStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return FactStatus.UNKNOWN;
        }
    }

    public static String renderMarkdown(Report report) {
        EvaluationMetrics m = report.metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation report — arm: ").append(m.arm()).append("\n\n");
        sb.append("> SYNTHETIC DATA ONLY. Generated from the labeled dataset ")
                .append("(`synthetic/dataset.json`) by the evaluation harness.\n\n");
        sb.append(String.format(Locale.ROOT, "- Claims evaluated: **%d**%n", m.total()));
        sb.append(String.format(Locale.ROOT, "- Accuracy: **%.3f** (%d/%d)%n", m.accuracy(), m.correct(), m.total()));
        sb.append(String.format(Locale.ROOT, "- Macro-F1: **%.3f**%n", m.macroF1()));
        sb.append(String.format(Locale.ROOT, "- False approval rate: **%.3f**%n", m.falseApprovalRate()));
        sb.append(String.format(Locale.ROOT, "- False rejection rate: **%.3f**%n", m.falseRejectionRate()));
        sb.append(String.format(Locale.ROOT, "- Human-review rate: **%.3f**%n", m.humanReviewRate()));
        sb.append(String.format(Locale.ROOT, "- Avg decision latency: **%.3f ms**%n", m.avgLatencyMs()));
        sb.append(String.format(Locale.ROOT, "- Estimated AI cost: **%s**%n%n",
                Double.isNaN(m.estimatedCostUsd()) ? "n/a" : String.format(Locale.ROOT, "$%.4f", m.estimatedCostUsd())));

        sb.append("## Per-class metrics\n\n");
        sb.append("| Decision | Precision | Recall | F1 | Support |\n");
        sb.append("|----------|-----------|--------|----|---------|\n");
        m.perClass().forEach((cls, cm) -> sb.append(String.format(Locale.ROOT,
                "| %s | %.3f | %.3f | %.3f | %d |%n", cls, cm.precision(), cm.recall(), cm.f1(), cm.support())));

        sb.append("\n## Per-claim results\n\n");
        sb.append("| Claim | Scenario | Expected | Predicted | OK |\n");
        sb.append("|-------|----------|----------|-----------|----|\n");
        for (EvaluationResult r : report.results()) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n",
                    r.claimNumber(), r.scenario(), r.expected(), r.predicted(), r.correct() ? "✅" : "❌"));
        }
        return sb.toString();
    }
}
