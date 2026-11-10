package com.healthclaim.decision.provider;

import com.healthclaim.decision.context.ClaimDecisionContext;
import com.healthclaim.rag.evidence.EvidenceResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an LLM decision provider is told: the fixed instructions and the controlled
 * slice of {@link ClaimDecisionContext} it may compare the claim against. Kept in one
 * place so the payload the model receives and the payload shown to users (live
 * analysis stream) are the same object, not two copies that can drift.
 */
public final class DecisionPromptPayload {

    /** System instructions for the semantic decision. */
    public static final String INSTRUCTIONS = """
            You are a health-insurance claims decision assistant for SYNTHETIC data.
            The deterministic rule engine already found NO hard objection (policy active,
            nothing excluded, within limits, required info present).
            Give a semantic judgment grounded ONLY in the provided facts and policy evidence:
            - "APPROVE" when the claim is clearly covered and consistent with the evidence.
            - "HUMAN_REVIEW" when anything is ambiguous, under-supported, or suspicious.
            - "REJECT" only when the policy evidence explicitly contradicts coverage.
            Never invent policy terms. Provide reasonCodes and a brief summary.
            Provide "confidence" in [0,1] only if you can estimate it, else null.""";

    private DecisionPromptPayload() {
    }

    /**
     * The claim, policy terms, extracted facts, retrieved policy evidence and rule
     * results: everything the model may use, and nothing else (no ids, no patient data).
     */
    public static Map<String, Object> build(ClaimDecisionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("diagnosis", ctx.claim().diagnosis());
        claim.put("procedure", ctx.claim().procedureName());
        claim.put("treatment", ctx.claim().treatment());
        claim.put("claimAmount", ctx.claim().claimAmount());
        claim.put("currency", ctx.claim().currency());
        payload.put("claim", claim);

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("coverageLimit", ctx.policy().coverageLimit());
        policy.put("exclusions", ctx.policy().exclusions());
        payload.put("policy", policy);

        payload.put("extractedFacts", ctx.extractedFacts());

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (EvidenceResult e : ctx.policyEvidence()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("section", e.section());
            item.put("page", e.pageNumber());
            item.put("text", e.snippet());
            evidence.add(item);
        }
        payload.put("policyEvidence", evidence);
        payload.put("ruleReasonCodes", ctx.ruleResults().stream().map(r -> r.code()).toList());
        return payload;
    }
}
