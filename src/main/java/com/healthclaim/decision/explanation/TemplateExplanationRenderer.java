package com.healthclaim.decision.explanation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, dependency-free explanation renderer. Used as the offline default
 * and as the fallback whenever the LLM explanation call fails, so an explanation is
 * always available. Produces plain-English text purely from the finalized decision
 * inputs: the verdict, why (rules and, if used, the AI judge), the policy wording it
 * relied on, and what a reviewer should check.
 */
@Component
public class TemplateExplanationRenderer {

    private static final Map<String, String> FRIENDLY = buildFriendly();
    private static final int MAX_CLAUSES = 3;
    private static final int CLAUSE_CHARS = 220;

    public String render(ExplanationInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(word(in.decision())).append(".** ").append(lead(in)).append("\n\n");

        List<String> why = new ArrayList<>();
        for (String code : nullSafe(in.reasonCodes())) {
            if (code.startsWith("DECISION_MODEL_")) {
                continue; // the AI's verdict is described by its summary below
            }
            why.add(describe(code));
        }
        if (in.model() != null && in.summary() != null && !in.summary().isBlank()) {
            why.add(in.summary().strip());
        }
        if (!why.isEmpty()) {
            sb.append("**Why**\n");
            new LinkedHashSet<>(why).forEach(w -> sb.append("- ").append(w).append('\n'));
            sb.append('\n');
        }

        List<String> terms = terms(in);
        List<String> quotes = new ArrayList<>();
        for (ExplanationInput.Clause c : distinctClauses(in.evidence())) {
            String excerpt = excerpt(c.text(), terms);
            if (excerpt != null && quotes.stream().noneMatch(q -> q.contains(excerpt))) {
                quotes.add((c.page() != null ? "Page " + c.page() + ": " : "") + '"' + excerpt + '"');
            }
        }
        if (!quotes.isEmpty()) {
            sb.append("**Policy wording it relied on**\n");
            quotes.forEach(q -> sb.append("- ").append(q).append('\n'));
            sb.append('\n');
        }

        List<String> check = new ArrayList<>();
        nullSafe(in.missingInformation()).forEach(m -> check.add("Missing: " + m));
        check.addAll(nullSafe(in.uncertainties()));
        if (!check.isEmpty()) {
            sb.append("**Worth checking**\n");
            new LinkedHashSet<>(check).forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        return sb.toString().strip();
    }

    /** Plain-English text for a reason code (shared with the LLM prompt). */
    public static String describe(String code) {
        return FRIENDLY.getOrDefault(code, humanize(code));
    }

    private static String lead(ExplanationInput in) {
        String what = describeClaim(in);
        return switch (in.decision() == null ? "" : in.decision()) {
            case "APPROVE" -> "The " + what + " is covered: every policy rule passed and the policy wording supports it.";
            case "REJECT" -> "The " + what + " is not payable under this policy.";
            default -> "A person needs to decide the " + what + " before anything is paid.";
        };
    }

    private static String describeClaim(ExplanationInput in) {
        String procedure = blankToNull(in.procedureName());
        String diagnosis = blankToNull(in.diagnosis());
        if (procedure != null && diagnosis != null) {
            return "claim for " + procedure + " (" + diagnosis + ")";
        }
        if (procedure != null || diagnosis != null) {
            return "claim for " + (procedure != null ? procedure : diagnosis);
        }
        return "claim";
    }

    private static String word(String decision) {
        if (decision == null) {
            return "Decided";
        }
        return switch (decision) {
            case "APPROVE" -> "Approved";
            case "REJECT" -> "Rejected";
            case "HUMAN_REVIEW" -> "Needs review";
            default -> decision;
        };
    }

    private static List<ExplanationInput.Clause> distinctClauses(List<ExplanationInput.Clause> clauses) {
        List<ExplanationInput.Clause> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExplanationInput.Clause c : nullSafe(clauses)) {
            String text = c.text() == null ? "" : c.text().strip();
            if (!text.isEmpty() && seen.add(text) && out.size() < MAX_CLAUSES) {
                out.add(c);
            }
        }
        return out;
    }

    /** Words from the claim (4+ letters) used to find the sentences that are about it. */
    private static List<String> terms(ExplanationInput in) {
        List<String> out = new ArrayList<>();
        for (String s : new String[] {in.procedureName(), in.diagnosis()}) {
            if (s == null) {
                continue;
            }
            for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (w.length() >= 4 && !out.contains(w)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /**
     * The sentence(s) of a chunk that mention the claim, or else its first complete
     * sentence. Chunks are cut at fixed sizes, so a leading fragment is skipped.
     */
    static String excerpt(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> sentences = new ArrayList<>();
        for (String s : text.replaceAll("\\s+", " ").strip().split("(?<=[.!?])\\s+(?=[A-Z0-9])")) {
            if (!s.isBlank()) {
                sentences.add(s.strip());
            }
        }
        if (sentences.isEmpty()) {
            return null;
        }
        List<String> matching = new ArrayList<>();
        for (String s : sentences) {
            String lower = s.toLowerCase(Locale.ROOT);
            if (terms.stream().anyMatch(lower::contains)) {
                matching.add(s);
            }
        }
        if (!matching.isEmpty()) {
            return trim(String.join(" ", matching.subList(0, Math.min(2, matching.size()))));
        }
        // No mention of the claim: the first sentence that starts cleanly (not a cut-off fragment).
        for (String s : sentences) {
            if (Character.isUpperCase(s.charAt(0)) || Character.isDigit(s.charAt(0))) {
                return trim(s);
            }
        }
        return trim(sentences.get(0));
    }

    private static String trim(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= CLAUSE_CHARS) {
            return flat;
        }
        int cut = flat.lastIndexOf(' ', CLAUSE_CHARS);
        return flat.substring(0, cut > CLAUSE_CHARS / 2 ? cut : CLAUSE_CHARS) + "…";
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String humanize(String code) {
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    private static Map<String, String> buildFriendly() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("POLICY_EXPIRED", "The policy was not in force on the treatment date (it had expired).");
        m.put("POLICY_CANCELLED", "The policy is cancelled.");
        m.put("POLICY_NOT_EFFECTIVE", "The treatment date is before the policy started.");
        m.put("POLICY_ACTIVE", "The policy was active on the treatment date.");
        m.put("PROCEDURE_EXCLUDED", "The procedure or diagnosis is on the policy's exclusion list.");
        m.put("NO_EXCLUSION_MATCH", "Nothing in the claim matches the policy's exclusion list.");
        m.put("CLAIM_EXCEEDS_COVERAGE_LIMIT", "The amount is above the policy's coverage limit.");
        m.put("WITHIN_COVERAGE_LIMIT", "The amount is within the policy's coverage limit.");
        m.put("WAITING_PERIOD_VIOLATION", "The treatment falls inside the policy's initial waiting period.");
        m.put("WAITING_PERIOD_SATISFIED", "The policy's waiting periods have passed.");
        m.put("PRE_EXISTING_WAITING_PERIOD", "The pre-existing condition is still inside its waiting period.");
        m.put("MISSING_REQUIRED_INFORMATION", "Required claim information is missing or unknown.");
        m.put("REQUIRED_INFORMATION_PRESENT", "All the required claim information is present.");
        m.put("CONFLICTING_INFORMATION", "The claim contains conflicting information.");
        m.put("NO_CONFLICTING_INFORMATION", "None of the claim's facts conflict.");
        m.put("PRE_EXISTING_STATUS_UNKNOWN", "It isn't known whether the condition was pre-existing.");
        m.put("PRE_EXISTING_STATUS_KNOWN", "Whether the condition was pre-existing is known.");
        m.put("RULES_ONLY_NO_OBJECTION", "The policy rules found no objection to the claim.");
        m.put("RAG_FAILURE", "The policy wording could not be retrieved, so a person must review.");
        m.put("AI_PROVIDER_FAILURE", "The AI judge was unavailable, so a person must review.");
        m.put("LOW_AI_CONFIDENCE", "The AI judge was not confident enough (below 50%), so a person must review.");
        m.put("AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS",
                "The AI judge suggested rejecting, but no policy rule supports that, so a person must confirm.");
        return Map.copyOf(m);
    }
}
