package com.healthclaim.claim.extraction;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.domain.FactSource;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.repository.ClaimFactRepository;
import com.healthclaim.claim.repository.ClaimRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Short transactional DB steps for extraction, kept separate so the (slow) LLM
 * call happens outside any transaction.
 */
@Component
public class ClaimExtractionStore {

    private final ClaimRepository claimRepository;
    private final ClaimFactRepository claimFactRepository;

    public ClaimExtractionStore(ClaimRepository claimRepository, ClaimFactRepository claimFactRepository) {
        this.claimRepository = claimRepository;
        this.claimFactRepository = claimFactRepository;
    }

    @Transactional(readOnly = true)
    public ClaimExtractionInput loadInput(UUID claimId) {
        Claim c = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
        return new ClaimExtractionInput(c.getProvider(), c.getDiagnosis(), c.getProcedureName(),
                c.getTreatment(), c.getHospitalizationStart(), c.getHospitalizationEnd(),
                c.getClaimAmount(), c.getCurrency());
    }

    @Transactional
    public void persistFacts(UUID claimId, List<ExtractedFact> facts) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));

        for (ExtractedFact fact : facts) {
            ClaimFact entity = claimFactRepository
                    .findByClaimIdAndFactKey(claimId, fact.key().wire())
                    .orElseGet(ClaimFact::new);
            entity.setClaim(claim);
            entity.setFactKey(fact.key().wire());
            entity.setFactValue(fact.value());
            entity.setValueType(fact.key().valueType());
            entity.setStatus(fact.status());
            entity.setSource(FactSource.EXTRACTED);
            entity.setConfidence(fact.confidence());
            claimFactRepository.save(entity);
        }

        if (claim.getStatus() == ClaimStatus.SUBMITTED) {
            claim.setStatus(ClaimStatus.PROCESSING);
            claimRepository.save(claim);
        }
    }

    @Transactional(readOnly = true)
    public List<ClaimFact> loadFacts(UUID claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }
        return claimFactRepository.findByClaimId(claimId);
    }
}
