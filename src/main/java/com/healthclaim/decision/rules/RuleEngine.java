package com.healthclaim.decision.rules;

import com.healthclaim.decision.context.DecisionInputs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link ClaimRule} and aggregates results with strict precedence:
 * REJECT &gt; REVIEW &gt; ALLOW. Rule beans are injected by Spring, so adding a rule
 * is just adding a bean.
 */
@Component
public class RuleEngine {

    private final List<ClaimRule> rules;

    public RuleEngine(List<ClaimRule> rules) {
        this.rules = rules;
    }

    public RuleEvaluation evaluate(DecisionInputs inputs) {
        List<RuleResult> results = new ArrayList<>();
        boolean reject = false;
        boolean review = false;
        for (ClaimRule rule : rules) {
            RuleResult result = rule.evaluate(inputs);
            if (result == null || result.outcome() == RuleOutcome.NOT_APPLICABLE) {
                if (result != null) {
                    results.add(result);
                }
                continue;
            }
            results.add(result);
            if (result.outcome() == RuleOutcome.REJECT) {
                reject = true;
            } else if (result.outcome() == RuleOutcome.REVIEW) {
                review = true;
            }
        }
        DeterministicOutcome outcome = reject ? DeterministicOutcome.REJECT
                : review ? DeterministicOutcome.REVIEW
                : DeterministicOutcome.ALLOW;
        return new RuleEvaluation(List.copyOf(results), outcome);
    }
}
