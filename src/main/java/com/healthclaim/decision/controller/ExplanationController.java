package com.healthclaim.decision.controller;

import com.healthclaim.decision.dto.ExplanationResponse;
import com.healthclaim.decision.explanation.ExplanationInput;
import com.healthclaim.decision.explanation.ExplanationService;
import com.healthclaim.decision.explanation.ExplanationStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Generates and persists a human-readable explanation for a claim's decision.
 * On-demand (not during analyze) to control AI cost. Never changes the decision.
 */
@RestController
@RequestMapping("/api/v1/claims/{claimId}")
@Tag(name = "Decisions", description = "Analyze claims and retrieve decisions and evidence")
public class ExplanationController {

    private final ExplanationStore store;
    private final ExplanationService explanationService;

    public ExplanationController(ExplanationStore store, ExplanationService explanationService) {
        this.store = store;
        this.explanationService = explanationService;
    }

    @PostMapping("/explain")
    @Operation(summary = "Generate a human-readable explanation for the claim's decision")
    public ExplanationResponse explain(@PathVariable UUID claimId) {
        ExplanationInput input = store.loadInput(claimId);     // short read tx (throws if not analyzed)
        ExplanationService.Explanation explanation = explanationService.generate(input); // LLM/template, outside tx
        store.saveExplanation(claimId, explanation.text());                              // short write tx
        return new ExplanationResponse(claimId, input.decision(), explanation.text(),
                explanation.source(), explanation.model());
    }
}
