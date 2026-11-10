package com.healthclaim.rag.controller;

import com.healthclaim.rag.evidence.EvidenceResult;
import com.healthclaim.rag.retrieval.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Debug/demonstration endpoint for RAG retrieval over a policy's ingested chunks.
 * The decision pipeline uses {@code RetrievalService} directly; this endpoint
 * makes retrieval inspectable.
 */
@RestController
@RequestMapping("/api/v1/policies/{policyId}")
@Validated
@Tag(name = "RAG retrieval", description = "Semantic search over a policy's ingested documents")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @GetMapping("/search")
    @Operation(summary = "Semantic search over a policy's ingested document chunks")
    public List<EvidenceResult> search(@PathVariable UUID policyId,
                                       @RequestParam @NotBlank String query,
                                       @RequestParam(required = false) Integer topK) {
        return retrievalService.retrieve(policyId, query, topK);
    }
}
