package com.healthclaim.claim.extraction;

import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.dto.ClaimExtractionResponse;
import com.healthclaim.claim.dto.ClaimFactResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates claim fact extraction: load controlled input (short tx) -> call the
 * LLM (no tx) -> persist validated facts (short tx). A provider/extraction failure
 * propagates as an exception; the decision pipeline (later) treats that as a signal
 * to route to HUMAN_REVIEW rather than guessing.
 */
@Service
public class ClaimExtractionService {

    private final ClaimExtractionStore store;
    private final ClaimFactExtractor extractor;

    public ClaimExtractionService(ClaimExtractionStore store, ClaimFactExtractor extractor) {
        this.store = store;
        this.extractor = extractor;
    }

    public ClaimExtractionResponse extract(UUID claimId) {
        ClaimExtractionInput input = store.loadInput(claimId);
        List<ExtractedFact> facts = extractor.extract(input);
        store.persistFacts(claimId, facts);
        return new ClaimExtractionResponse(claimId, ClaimStatus.PROCESSING.name(),
                facts.stream().map(ClaimExtractionService::toDto).toList());
    }

    public List<ClaimFactResponse> getFacts(UUID claimId) {
        return store.loadFacts(claimId).stream().map(ClaimExtractionService::toDto).toList();
    }

    private static ClaimFactResponse toDto(ExtractedFact fact) {
        return new ClaimFactResponse(fact.key().wire(), fact.value(), fact.key().valueType(),
                fact.status().name(), "EXTRACTED", fact.confidence());
    }

    private static ClaimFactResponse toDto(ClaimFact fact) {
        return new ClaimFactResponse(fact.getFactKey(), fact.getFactValue(), fact.getValueType(),
                fact.getStatus() != null ? fact.getStatus().name() : null,
                fact.getSource() != null ? fact.getSource().name() : null,
                fact.getConfidence());
    }
}
