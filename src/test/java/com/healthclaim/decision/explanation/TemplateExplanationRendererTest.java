package com.healthclaim.decision.explanation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateExplanationRendererTest {

    private final TemplateExplanationRenderer renderer = new TemplateExplanationRenderer();

    private static final String CLAUSE = "3.1 Cholecystectomy: removal of the gall bladder for gallstones or "
            + "cholecystitis is covered as an inpatient procedure.";

    @Test
    void anAiApprovalReadsAsAnExplanationNotAListOfCodes() {
        ExplanationInput input = new ExplanationInput("APPROVE",
                List.of("POLICY_ACTIVE", "NO_EXCLUSION_MATCH", "WITHIN_COVERAGE_LIMIT", "DECISION_MODEL_APPROVE"),
                List.of(), List.of("The decision model consulted 10 web source(s); only the policy evidence should decide a fictional policy."),
                List.of(new ExplanationInput.Clause(1, "SYNTHETIC POLICY WORDING - POL-SYN-001", CLAUSE),
                        new ExplanationInput.Clause(1, "SYNTHETIC POLICY WORDING - POL-SYN-001", CLAUSE)),
                "Gallstones", "Laparoscopic Cholecystectomy",
                "Decision model chose approve with 64% probability (approve 64%, human review 35%, reject 1%).",
                0.64, "parallel/decisions-fast-20261106");

        String text = renderer.render(input);

        assertThat(text).startsWith("**Approved.** The claim for Laparoscopic Cholecystectomy (Gallstones) is covered");
        assertThat(text).contains("- The policy was active on the treatment date.")
                .contains("- Nothing in the claim matches the policy's exclusion list.")
                .contains("- Decision model chose approve with 64% probability");
        assertThat(text).doesNotContain("decision model approve").doesNotContain("DECISION_MODEL_");
        // The policy text itself is quoted, once, not just its heading.
        assertThat(text).contains("Page 1: \"3.1 Cholecystectomy: removal of the gall bladder");
        assertThat(text.split("3.1 Cholecystectomy", -1)).hasSize(2);
        assertThat(text).contains("**Worth checking**").contains("10 web source(s)");
    }

    @Test
    void aRulesRejectionNeedsNoAiLine() {
        ExplanationInput input = new ExplanationInput("REJECT", List.of("PROCEDURE_EXCLUDED"), List.of(), List.of(),
                List.of(), "Nasal deformity", "Rhinoplasty", "Rejected by deterministic policy rules.", null, null);

        String text = renderer.render(input);

        assertThat(text).startsWith("**Rejected.** The claim for Rhinoplasty (Nasal deformity) is not payable");
        assertThat(text).contains("- The procedure or diagnosis is on the policy's exclusion list.");
        assertThat(text).doesNotContain("deterministic policy rules").doesNotContain("Worth checking");
    }

    @Test
    void reviewListsWhatIsMissing() {
        ExplanationInput input = new ExplanationInput("HUMAN_REVIEW", List.of("MISSING_REQUIRED_INFORMATION"),
                List.of("diagnosis"), List.of(), List.of(), null, null, null, null, null);

        String text = renderer.render(input);

        assertThat(text).startsWith("**Needs review.** A person needs to decide the claim");
        assertThat(text).contains("- Missing: diagnosis");
    }

    @Test
    void quotesTheSentenceAboutTheClaimNotAChunkFragment() {
        // Chunks are cut at fixed sizes, so they can start mid-word.
        String chunk = "stones or cholecystitis. 3.2 Appendectomy: surgical removal of the appendix is covered. "
                + "3.3 Cholecystectomy: removal of the gall bladder is covered as an inpatient procedure.";
        assertThat(TemplateExplanationRenderer.excerpt(chunk, List.of("cholecystectomy", "gallstones")))
                .isEqualTo("3.3 Cholecystectomy: removal of the gall bladder is covered as an inpatient procedure.");
        // Nothing about the claim: the first sentence that starts cleanly.
        assertThat(TemplateExplanationRenderer.excerpt(chunk, List.of("rhinoplasty")))
                .startsWith("3.2 Appendectomy");
    }

    @Test
    void longClausesAreTrimmedAtAWord() {
        String longText = "word ".repeat(80).strip();
        ExplanationInput input = new ExplanationInput("APPROVE", List.of(), List.of(), List.of(),
                List.of(new ExplanationInput.Clause(2, null, longText)), null, "X", null, null, null);

        String quoted = renderer.render(input).lines().filter(l -> l.startsWith("- Page 2:")).findFirst().orElseThrow();
        assertThat(quoted).endsWith("…\"").hasSizeLessThan(240);
    }
}
