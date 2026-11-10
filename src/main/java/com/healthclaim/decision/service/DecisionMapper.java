package com.healthclaim.decision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.domain.DecisionEvidence;
import com.healthclaim.decision.dto.DecisionResponse;
import com.healthclaim.decision.dto.EvidenceResponse;
import com.healthclaim.rag.evidence.EvidenceResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Maps decision outcomes/entities to API DTOs, parsing the JSON-text list columns.
 */
@Component
public class DecisionMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public DecisionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecisionResponse fromOutcome(UUID claimId, UUID decisionId, Instant createdAt, DecisionOutcome o) {
        List<EvidenceResponse> evidence = o.evidence().stream()
                .map(DecisionMapper::toEvidenceResponse)
                .toList();
        return new DecisionResponse(decisionId, claimId, o.decision().name(), o.confidence(),
                o.reasonCodes(), o.missingInformation(), o.uncertainties(), evidence, o.summary(),
                o.modelProvider(), o.model(), o.promptVersion(), o.latencyMs(), null, createdAt);
    }

    public DecisionResponse fromEntity(Decision d, List<DecisionEvidence> evidenceRows) {
        List<EvidenceResponse> evidence = evidenceRows.stream()
                .map(DecisionMapper::toEvidenceResponse)
                .toList();
        return new DecisionResponse(
                d.getId(),
                d.getClaim() != null ? d.getClaim().getId() : null,
                d.getDecision() != null ? d.getDecision().name() : null,
                d.getConfidence(),
                parseList(d.getReasonCodes()),
                parseList(d.getMissingInformation()),
                parseList(d.getUncertainties()),
                evidence,
                d.getSummary(),
                d.getModelProvider(),
                d.getModelName(),
                d.getPromptVersion(),
                d.getLatencyMs(),
                d.getExplanation(),
                d.getCreatedAt());
    }

    private static EvidenceResponse toEvidenceResponse(EvidenceResult e) {
        return new EvidenceResponse(e.chunkId(), e.documentId(), e.policyId(), e.pageNumber(),
                e.section(), e.heading(), e.similarity(), null, e.snippet());
    }

    private static EvidenceResponse toEvidenceResponse(DecisionEvidence e) {
        return new EvidenceResponse(e.getPolicyChunkId(), e.getDocumentId(), e.getPolicyId(),
                e.getPageNumber(), e.getSection(), null, e.getSimilarity(), e.getRank(), e.getSnippet());
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    public String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }
}
