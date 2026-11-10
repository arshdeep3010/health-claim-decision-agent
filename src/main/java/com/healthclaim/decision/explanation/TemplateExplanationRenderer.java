package com.healthclaim.decision.explanation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, dependency-free explanation renderer. Used as the offline default
 * and as the fallback whenever the LLM explanation call fails, so an explanation is
 * always available. Produces text purely from the finalized decision inputs.
 */
@Component
public class TemplateExplanationRenderer {

    private static final Map<String, String> FRIENDLY = buildFriendly();

    public String render(ExplanationInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("Decision: ").append(in.decision()).append("\n\n");

        sb.append("Reasons:\n");
        if (in.reasonCodes() == null || in.reasonCodes().isEmpty()) {
            sb.append("- No specific reason codes were recorded.\n");
        } else {
            for (String code : in.reasonCodes()) {
                sb.append("- ").append(FRIENDLY.getOrDefault(code, humanize(code))).append('\n');
            }
        }

        if (notEmpty(in.evidenceRefs())) {
            sb.append("\nSupporting policy evidence:\n");
            in.evidenceRefs().forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        if (notEmpty(in.missingInformation())) {
            sb.append("\nMissing information:\n");
            in.missingInformation().forEach(m -> sb.append("- ").append(m).append('\n'));
        }
        if (notEmpty(in.uncertainties())) {
            sb.append("\nUncertainties:\n");
            in.uncertainties().forEach(u -> sb.append("- ").append(u).append('\n'));
        }
        return sb.toString().strip();
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    private static String humanize(String code) {
        return code.toLowerCase().replace('_', ' ');
    }

    private static Map<String, String> buildFriendly() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("POLICY_EXPIRED", "The policy was not in force on the service date (expired).");
        m.put("POLICY_CANCELLED", "The policy is cancelled.");
        m.put("POLICY_NOT_EFFECTIVE", "The service date is before the policy's effective date.");
        m.put("POLICY_ACTIVE", "The policy is active and in force on the service date.");
        m.put("PROCEDURE_EXCLUDED", "The procedure/diagnosis is explicitly excluded by the policy.");
        m.put("NO_EXCLUSION_MATCH", "No policy exclusion matched the claim.");
        m.put("CLAIM_EXCEEDS_COVERAGE_LIMIT", "The claim amount exceeds the policy coverage limit.");
        m.put("WITHIN_COVERAGE_LIMIT", "The claim amount is within the coverage limit.");
        m.put("WAITING_PERIOD_VIOLATION", "The claim falls within the policy's initial waiting period.");
        m.put("PRE_EXISTING_WAITING_PERIOD", "The pre-existing condition is within its waiting period.");
        m.put("MISSING_REQUIRED_INFORMATION", "Required claim information is missing or unknown.");
        m.put("CONFLICTING_INFORMATION", "The claim contains conflicting information.");
        m.put("PRE_EXISTING_STATUS_UNKNOWN", "The pre-existing condition status is uncertain.");
        m.put("PRE_EXISTING_STATUS_KNOWN", "The pre-existing condition status is known.");
        m.put("RULES_ONLY_NO_OBJECTION", "Deterministic rules found no objection to the claim.");
        m.put("RAG_FAILURE", "Policy evidence could not be retrieved; a human must review.");
        m.put("AI_PROVIDER_FAILURE", "The AI assistant was unavailable; a human must review.");
        m.put("LOW_AI_CONFIDENCE", "The AI assistant's confidence was below the required threshold.");
        m.put("AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS",
                "The AI suggested rejection but no policy rule supports it; a human must confirm.");
        return m;
    }
}
