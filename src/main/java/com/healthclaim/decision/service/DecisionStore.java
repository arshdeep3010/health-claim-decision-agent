package com.healthclaim.decision.service;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.claim.repository.ClaimFactRepository;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.decision.context.ClaimFactsView;
import com.healthclaim.decision.context.ClaimSummary;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.domain.DecisionEvidence;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.repository.DecisionEvidenceRepository;
import com.healthclaim.decision.repository.DecisionRepository;
import com.healthclaim.rag.evidence.EvidenceResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Short transactional DB steps for the decision pipeline, isolated so RAG and AI
 * calls happen outside any transaction.
 */
@Component
public class DecisionStore {

    private final ClaimRepository claimRepository;
    private final ClaimFactRepository claimFactRepository;
    private final DecisionRepository decisionRepository;
    private final DecisionEvidenceRepository decisionEvidenceRepository;
    private final PolicyFactsLoader policyFactsLoader;
    private final DecisionMapper decisionMapper;

    public DecisionStore(ClaimRepository claimRepository,
                         ClaimFactRepository claimFactRepository,
                         DecisionRepository decisionRepository,
                         DecisionEvidenceRepository decisionEvidenceRepository,
                         PolicyFactsLoader policyFactsLoader,
                         DecisionMapper decisionMapper) {
        this.claimRepository = claimRepository;
        this.claimFactRepository = claimFactRepository;
        this.decisionRepository = decisionRepository;
        this.decisionEvidenceRepository = decisionEvidenceRepository;
        this.policyFactsLoader = policyFactsLoader;
        this.decisionMapper = decisionMapper;
    }

    @Transactional(readOnly = true)
    public AnalysisData loadAnalysisData(UUID claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));

        ClaimSummary summary = new ClaimSummary(claim.getId(), claim.getClaimNumber(), claim.getProvider(),
                claim.getDiagnosis(), claim.getProcedureName(), claim.getTreatment(),
                claim.getHospitalizationStart(), claim.getHospitalizationEnd(),
                claim.getClaimAmount(), claim.getCurrency());

        ClaimFactsView facts = buildFactsView(claimFactRepository.findByClaimId(claimId));

        return new AnalysisData(summary, policyFactsLoader.load(claim.getPolicy()),
                facts, claim.getPolicy().getId());
    }

    @Transactional(readOnly = true)
    public Optional<Decision> findByIdempotencyKey(String key) {
        return decisionRepository.findByIdempotencyKey(key);
    }

    @Transactional
    public Decision saveDecision(UUID claimId, DecisionOutcome outcome, String idempotencyKey) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));

        Decision decision = decisionRepository.findByClaimId(claimId).orElseGet(Decision::new);
        decision.setClaim(claim);
        decision.setDecision(outcome.decision());
        decision.setConfidence(outcome.confidence());
        decision.setReasonCodes(decisionMapper.toJson(outcome.reasonCodes()));
        decision.setMissingInformation(decisionMapper.toJson(outcome.missingInformation()));
        decision.setUncertainties(decisionMapper.toJson(outcome.uncertainties()));
        decision.setSummary(outcome.summary());
        // An explanation describes one specific decision; a re-analysis must not inherit the old one.
        decision.setExplanation(null);
        decision.setSupportingEvidence(decisionMapper.toJson(evidenceRefs(outcome.evidence())));
        decision.setModelProvider(outcome.modelProvider());
        decision.setModelName(outcome.model());
        decision.setPromptVersion(outcome.promptVersion());
        decision.setLatencyMs(outcome.latencyMs());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            decision.setIdempotencyKey(idempotencyKey);
        }
        Decision saved = decisionRepository.save(decision);

        // Replace evidence snapshot.
        decisionEvidenceRepository.deleteByDecisionId(saved.getId());
        int rank = 0;
        for (EvidenceResult e : outcome.evidence()) {
            DecisionEvidence de = new DecisionEvidence();
            de.setDecision(saved);
            de.setPolicyChunkId(e.chunkId());
            de.setDocumentId(e.documentId());
            de.setPolicyId(e.policyId());
            de.setPageNumber(e.pageNumber());
            de.setSection(e.section());
            de.setSimilarity(e.similarity());
            de.setRank(rank++);
            de.setSnippet(e.snippet());
            decisionEvidenceRepository.save(de);
        }

        claim.setStatus(toClaimStatus(outcome.decision()));
        claimRepository.save(claim);

        return saved;
    }

    @Transactional(readOnly = true)
    public Decision requireDecision(UUID claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }
        return decisionRepository.findByClaimId(claimId)
                .orElseThrow(() -> new com.healthclaim.decision.exception.DecisionNotFoundException(claimId));
    }

    @Transactional(readOnly = true)
    public List<DecisionEvidence> loadEvidence(UUID decisionId) {
        return decisionEvidenceRepository.findByDecisionIdOrderByRankAsc(decisionId);
    }

    private ClaimFactsView buildFactsView(List<ClaimFact> rows) {
        Map<FactKey, ClaimFactsView.FactSnapshot> map = new EnumMap<>(FactKey.class);
        for (ClaimFact row : rows) {
            FactKey.fromWire(row.getFactKey()).ifPresent(key ->
                    map.put(key, new ClaimFactsView.FactSnapshot(row.getFactValue(), row.getStatus(), row.getConfidence())));
        }
        return new ClaimFactsView(map);
    }

    private static List<String> evidenceRefs(List<EvidenceResult> evidence) {
        return evidence.stream()
                .map(e -> (e.section() != null ? e.section() : "policy")
                        + (e.pageNumber() != null ? " (p." + e.pageNumber() + ")" : ""))
                .toList();
    }

    private static ClaimStatus toClaimStatus(DecisionType decision) {
        return switch (decision) {
            case APPROVE -> ClaimStatus.APPROVED;
            case REJECT -> ClaimStatus.REJECTED;
            case HUMAN_REVIEW -> ClaimStatus.HUMAN_REVIEW;
        };
    }
}
