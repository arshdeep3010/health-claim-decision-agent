package com.healthclaim.evaluation;

import com.healthclaim.decision.domain.DecisionType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Classification metrics computed from actual evaluation results — never fabricated.
 * Includes accuracy, per-class precision/recall/F1, macro-F1, the safety-relevant
 * false-approval/false-rejection rates, the human-review rate, plus latency and
 * (where applicable) estimated AI cost for the arm.
 */
public record EvaluationMetrics(
        String arm,
        int total,
        long correct,
        double accuracy,
        Map<DecisionType, ClassMetric> perClass,
        double macroF1,
        double falseApprovalRate,
        double falseRejectionRate,
        double humanReviewRate,
        double avgLatencyMs,
        double estimatedCostUsd
) {
    public record ClassMetric(double precision, double recall, double f1, long support) {
    }

    public static EvaluationMetrics compute(String arm, List<EvaluationResult> results,
                                            double avgLatencyMs, double estimatedCostUsd) {
        int total = results.size();
        long correct = results.stream().filter(EvaluationResult::correct).count();
        double accuracy = total == 0 ? 0.0 : (double) correct / total;

        Map<DecisionType, ClassMetric> perClass = new EnumMap<>(DecisionType.class);
        double f1Sum = 0.0;
        for (DecisionType c : DecisionType.values()) {
            long tp = results.stream().filter(r -> r.predicted() == c && r.expected() == c).count();
            long fp = results.stream().filter(r -> r.predicted() == c && r.expected() != c).count();
            long fn = results.stream().filter(r -> r.predicted() != c && r.expected() == c).count();
            long support = results.stream().filter(r -> r.expected() == c).count();
            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            perClass.put(c, new ClassMetric(precision, recall, f1, support));
            f1Sum += f1;
        }
        double macroF1 = f1Sum / DecisionType.values().length;

        double falseApproval = rate(results, DecisionType.APPROVE, total);
        double falseRejection = rate(results, DecisionType.REJECT, total);
        double humanReviewRate = total == 0 ? 0.0
                : (double) results.stream().filter(r -> r.predicted() == DecisionType.HUMAN_REVIEW).count() / total;

        return new EvaluationMetrics(arm, total, correct, accuracy, perClass, macroF1,
                falseApproval, falseRejection, humanReviewRate, avgLatencyMs, estimatedCostUsd);
    }

    /** Fraction of all claims predicted as {@code type} but whose true label was not {@code type}. */
    private static double rate(List<EvaluationResult> results, DecisionType type, int total) {
        if (total == 0) {
            return 0.0;
        }
        return (double) results.stream()
                .filter(r -> r.predicted() == type && r.expected() != type).count() / total;
    }
}
