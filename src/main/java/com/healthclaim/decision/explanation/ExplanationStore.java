package com.healthclaim.decision.explanation;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.dto.DecisionResponse;
import com.healthclaim.decision.exception.DecisionNotFoundException;
import com.healthclaim.decision.repository.DecisionEvidenceRepository;
import com.healthclaim.decision.repository.DecisionRepository;
import com.healthclaim.decision.service.DecisionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Short transactional steps for explanation generation, so the LLM call runs
 * outside any transaction.
 */
@Component
public class ExplanationStore {

    private final ClaimRepository claimRepository;
    private final DecisionRepository decisionRepository;
    private final DecisionEvidenceRepository decisionEvidenceRepository;
    private final DecisionMapper decisionMapper;

    public ExplanationStore(ClaimRepository claimRepository,
                            DecisionRepository decisionRepository,
                            DecisionEvidenceRepository decisionEvidenceRepository,
                            DecisionMapper decisionMapper) {
        this.claimRepository = claimRepository;
        this.decisionRepository = decisionRepository;
        this.decisionEvidenceRepository = decisionEvidenceRepository;
        this.decisionMapper = decisionMapper;
    }

    @Transactional(readOnly = true)
    public ExplanationInput loadInput(UUID claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
        Decision decision = decisionRepository.findByClaimId(claimId)
                .orElseThrow(() -> new DecisionNotFoundException(claimId));

        DecisionResponse dr = decisionMapper.fromEntity(decision,
                decisionEvidenceRepository.findByDecisionIdOrderByRankAsc(decision.getId()));

        List<ExplanationInput.Clause> evidence = dr.supportingEvidence().stream()
                .filter(e -> e.snippet() != null && !e.snippet().isBlank())
                .map(e -> new ExplanationInput.Clause(e.pageNumber(), e.section(), e.snippet()))
                .toList();
        String model = "deterministic".equals(dr.modelProvider()) ? null : dr.model();
        return new ExplanationInput(dr.decision(), dr.reasonCodes(), dr.missingInformation(),
                dr.uncertainties(), evidence, claim.getDiagnosis(), claim.getProcedureName(),
                dr.summary(), dr.confidence(), model);
    }

    @Transactional
    public String saveExplanation(UUID claimId, String explanation) {
        Decision decision = decisionRepository.findByClaimId(claimId)
                .orElseThrow(() -> new DecisionNotFoundException(claimId));
        decision.setExplanation(explanation);
        decisionRepository.save(decision);
        return explanation;
    }

    @Transactional(readOnly = true)
    public String currentDecision(UUID claimId) {
        return decisionRepository.findByClaimId(claimId)
                .map(d -> d.getDecision() != null ? d.getDecision().name() : null)
                .orElseThrow(() -> new DecisionNotFoundException(claimId));
    }
}
