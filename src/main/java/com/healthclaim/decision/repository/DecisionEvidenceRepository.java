package com.healthclaim.decision.repository;

import com.healthclaim.decision.domain.DecisionEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DecisionEvidenceRepository extends JpaRepository<DecisionEvidence, UUID> {
    List<DecisionEvidence> findByDecisionIdOrderByRankAsc(UUID decisionId);

    void deleteByDecisionId(UUID decisionId);
}
