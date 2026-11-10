package com.healthclaim.decision.repository;

import com.healthclaim.decision.domain.Decision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {
    Optional<Decision> findByClaimId(UUID claimId);

    Optional<Decision> findByIdempotencyKey(String idempotencyKey);
}
