package com.healthclaim.decision.rules;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.decision.context.ClaimFactsView;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.context.DecisionInputs;
import com.healthclaim.decision.context.PolicyFacts;
import com.healthclaim.decision.rules.impl.ConflictingFactsRule;
import com.healthclaim.decision.rules.impl.CoverageLimitRule;
import com.healthclaim.decision.rules.impl.ExclusionRule;
import com.healthclaim.decision.rules.impl.MissingInformationRule;
import com.healthclaim.decision.rules.impl.PolicyActiveRule;
import com.healthclaim.decision.rules.impl.PreExistingConditionRule;
import com.healthclaim.decision.rules.impl.WaitingPeriodRule;
import com.healthclaim.policy.domain.PolicyStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine(List.of(
            new PolicyActiveRule(), new ExclusionRule(), new CoverageLimitRule(),
            new WaitingPeriodRule(), new MissingInformationRule(),
            new ConflictingFactsRule(), new PreExistingConditionRule()));

    @Test
    void cleanClaimAllows() {
        DecisionInputs inputs = inputs(activePolicy(List.of(), new BigDecimal("500000")),
                new BigDecimal("145000"), cleanFacts());
        assertThat(engine.evaluate(inputs).outcome()).isEqualTo(DeterministicOutcome.ALLOW);
    }

    @Test
    void expiredPolicyRejects() {
        PolicyFacts expired = new PolicyFacts("P", PolicyStatus.EXPIRED,
                LocalDate.now().minusYears(2), LocalDate.now().minusDays(1),
                new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO, "INR", List.of(), Map.of());
        RuleEvaluation eval = engine.evaluate(inputs(expired, new BigDecimal("1000"), cleanFacts()));
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REJECT);
        assertThat(eval.drivingReasonCodes()).contains("POLICY_EXPIRED");
    }

    @Test
    void excludedProcedureRejects() {
        DecisionInputs inputs = inputs(activePolicy(List.of("Cosmetic surgery"), new BigDecimal("500000")),
                new BigDecimal("1000"), cleanFacts(), "Cosmetic surgery");
        RuleEvaluation eval = engine.evaluate(inputs);
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REJECT);
        assertThat(eval.drivingReasonCodes()).contains("PROCEDURE_EXCLUDED");
    }

    @Test
    void overLimitReviews() {
        DecisionInputs inputs = inputs(activePolicy(List.of(), new BigDecimal("1000")),
                new BigDecimal("5000"), cleanFacts());
        RuleEvaluation eval = engine.evaluate(inputs);
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REVIEW);
        assertThat(eval.drivingReasonCodes()).contains("CLAIM_EXCEEDS_COVERAGE_LIMIT");
    }

    @Test
    void missingDiagnosisReviews() {
        ClaimFactsView facts = new ClaimFactsView(Map.of(
                FactKey.PROCEDURE, snap("Chole", FactStatus.KNOWN),
                FactKey.PRE_EXISTING_CONDITION, snap("false", FactStatus.KNOWN)));
        RuleEvaluation eval = engine.evaluate(inputs(activePolicy(List.of(), new BigDecimal("500000")),
                new BigDecimal("1000"), facts));
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REVIEW);
        assertThat(eval.drivingReasonCodes()).contains("MISSING_REQUIRED_INFORMATION");
    }

    @Test
    void unknownPreExistingReviews() {
        ClaimFactsView facts = new ClaimFactsView(Map.of(
                FactKey.DIAGNOSIS, snap("Gallstones", FactStatus.KNOWN),
                FactKey.PROCEDURE, snap("Chole", FactStatus.KNOWN)));
        RuleEvaluation eval = engine.evaluate(inputs(activePolicy(List.of(), new BigDecimal("500000")),
                new BigDecimal("1000"), facts));
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REVIEW);
        assertThat(eval.drivingReasonCodes()).contains("PRE_EXISTING_STATUS_UNKNOWN");
    }

    @Test
    void conflictingFactsReview() {
        ClaimFactsView facts = new ClaimFactsView(Map.of(
                FactKey.DIAGNOSIS, snap("Gallstones", FactStatus.KNOWN),
                FactKey.PROCEDURE, snap("Chole", FactStatus.KNOWN),
                FactKey.PRE_EXISTING_CONDITION, snap("false", FactStatus.KNOWN),
                FactKey.CLAIM_AMOUNT, snap("1", FactStatus.CONFLICTING)));
        RuleEvaluation eval = engine.evaluate(inputs(activePolicy(List.of(), new BigDecimal("500000")),
                new BigDecimal("1000"), facts));
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REVIEW);
        assertThat(eval.drivingReasonCodes()).contains("CONFLICTING_INFORMATION");
    }

    @Test
    void waitingPeriodViolationRejects() {
        // Policy effective 3 months ago with a 12-month general waiting period;
        // service date (now-3d) falls inside it.
        PolicyFacts policy = new PolicyFacts("P", PolicyStatus.ACTIVE,
                LocalDate.now().minusMonths(3), LocalDate.now().plusYears(1),
                new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO, "INR",
                List.of(), Map.of("GENERAL", 12));
        RuleEvaluation eval = engine.evaluate(inputs(policy, new BigDecimal("1000"), cleanFacts()));
        assertThat(eval.outcome()).isEqualTo(DeterministicOutcome.REJECT);
        assertThat(eval.drivingReasonCodes()).contains("WAITING_PERIOD_VIOLATION");
    }

    @Test
    void rejectTakesPrecedenceOverReview() {
        // Expired policy (REJECT) AND missing facts (REVIEW) -> REJECT wins.
        PolicyFacts expired = new PolicyFacts("P", PolicyStatus.EXPIRED,
                LocalDate.now().minusYears(2), LocalDate.now().minusDays(1),
                new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO, "INR", List.of(), Map.of());
        ClaimFactsView empty = new ClaimFactsView(Map.of());
        assertThat(engine.evaluate(inputs(expired, new BigDecimal("1000"), empty)).outcome())
                .isEqualTo(DeterministicOutcome.REJECT);
    }

    // --- helpers ---

    private static ClaimFactsView.FactSnapshot snap(String value, FactStatus status) {
        return new ClaimFactsView.FactSnapshot(value, status, null);
    }

    private static ClaimFactsView cleanFacts() {
        return new ClaimFactsView(Map.of(
                FactKey.DIAGNOSIS, snap("Gallstones", FactStatus.KNOWN),
                FactKey.PROCEDURE, snap("Laparoscopic Cholecystectomy", FactStatus.KNOWN),
                FactKey.PRE_EXISTING_CONDITION, snap("false", FactStatus.KNOWN)));
    }

    private static PolicyFacts activePolicy(List<String> exclusions, BigDecimal limit) {
        return new PolicyFacts("P", PolicyStatus.ACTIVE,
                LocalDate.now().minusYears(1), LocalDate.now().plusYears(1),
                limit, BigDecimal.ZERO, BigDecimal.ZERO, "INR", exclusions, Map.of());
    }

    private static DecisionInputs inputs(PolicyFacts policy, BigDecimal amount, ClaimFactsView facts) {
        return inputs(policy, amount, facts, "Laparoscopic Cholecystectomy");
    }

    private static DecisionInputs inputs(PolicyFacts policy, BigDecimal amount, ClaimFactsView facts, String procedure) {
        ClaimSummary claim = new ClaimSummary(null, "CLM-1", "Hospital", "Gallstones",
                procedure, "inpatient", LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(1), amount, "INR");
        return new DecisionInputs(claim, policy, facts, List.of());
    }
}
