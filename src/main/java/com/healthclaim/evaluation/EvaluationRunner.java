package com.healthclaim.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.synthetic.SyntheticDataLoader;
import com.healthclaim.synthetic.model.SyntheticDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the evaluation harness and writes a report. Activated with the {@code eval}
 * profile:  {@code java -jar app.jar --spring.profiles.active=eval}
 * (or run the {@code EvaluationHarnessTest} which also writes a report artifact).
 */
@Component
@Profile("eval")
public class EvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final ObjectMapper objectMapper;

    public EvaluationRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        SyntheticDataset dataset = SyntheticDataLoader.load(objectMapper);
        EvaluationHarness.Report report =
                EvaluationHarness.evaluate(dataset, "RULE_ONLY", EvaluationHarness.ruleOnlyPredictor());

        Path out = Path.of("evaluation-report.md");
        Files.writeString(out, EvaluationHarness.renderMarkdown(report));
        EvaluationMetrics m = report.metrics();
        log.info("Evaluation [{}]: accuracy={} macroF1={} humanReviewRate={} → report written to {}",
                m.arm(), String.format("%.3f", m.accuracy()), String.format("%.3f", m.macroF1()),
                String.format("%.3f", m.humanReviewRate()), out.toAbsolutePath());
    }
}
