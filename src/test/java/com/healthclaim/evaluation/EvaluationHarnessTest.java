package com.healthclaim.evaluation;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.healthclaim.synthetic.SyntheticDataLoader;
import com.healthclaim.synthetic.model.SyntheticDataset;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the evaluation harness offline over the full synthetic dataset and writes a
 * report artifact. The RULE_ONLY arm must reproduce the rule-grounded labels
 * (accuracy 1.0) — this doubles as a consistency check that the dataset labels
 * agree with the rule engine.
 */
class EvaluationHarnessTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void ruleOnlyArmReproducesLabelsAndWritesReport() throws Exception {
        SyntheticDataset dataset = SyntheticDataLoader.load(mapper);
        EvaluationHarness.Report report =
                EvaluationHarness.evaluate(dataset, "RULE_ONLY", EvaluationHarness.ruleOnlyPredictor());

        assertThat(report.results()).hasSize(30);
        assertThat(report.metrics().total()).isEqualTo(30);
        // Labels are rule-grounded, so the deterministic arm must match them exactly.
        assertThat(report.metrics().accuracy())
                .as("rule-only arm should reproduce the rule-grounded labels")
                .isEqualTo(1.0);
        assertThat(report.metrics().humanReviewRate()).isBetween(0.0, 1.0);

        Path out = Path.of("target/evaluation/rule-only.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, EvaluationHarness.renderMarkdown(report));
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(0);
    }
}
