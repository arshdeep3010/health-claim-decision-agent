package com.healthclaim.decision.explanation;

import java.util.List;

/**
 * The finalized, structured inputs an explanation is generated FROM. The
 * explanation layer may only read these; it can never change the decision.
 *
 * @param summary   the stored one-line summary (for an AI decision, the model's
 *                  verdict and probabilities)
 * @param evidence  the policy clauses the decision relied on, with their text
 */
public record ExplanationInput(
        String decision,
        List<String> reasonCodes,
        List<String> missingInformation,
        List<String> uncertainties,
        List<Clause> evidence,
        String diagnosis,
        String procedureName,
        String summary,
        Double confidence,
        String model
) {

    /** One retrieved policy clause: where it is and what it says. */
    public record Clause(Integer page, String section, String text) {
    }
}
