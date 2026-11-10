package com.healthclaim.decision.service;

import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.dto.DecisionResponse;
import com.healthclaim.decision.dto.EvidenceResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Read-side queries for a claim's stored decision and evidence.
 */
@Service
public class DecisionQueryService {

    private final DecisionStore store;
    private final DecisionMapper mapper;

    public DecisionQueryService(DecisionStore store, DecisionMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public DecisionResponse getDecision(UUID claimId) {
        Decision decision = store.requireDecision(claimId);
        return mapper.fromEntity(decision, store.loadEvidence(decision.getId()));
    }

    public List<EvidenceResponse> getEvidence(UUID claimId) {
        Decision decision = store.requireDecision(claimId);
        return mapper.fromEntity(decision, store.loadEvidence(decision.getId())).supportingEvidence();
    }
}
