package com.healthclaim.decision.explanation;

import java.util.List;

/**
 * The finalized, structured inputs an explanation is generated FROM. The
 * explanation layer may only read these; it can never change the decision.
 */
public record ExplanationInput(
        String decision,
        List<String> reasonCodes,
        List<String> missingInformation,
        List<String> uncertainties,
        List<String> evidenceRefs,
        String diagnosis,
        String procedureName
) {
}
