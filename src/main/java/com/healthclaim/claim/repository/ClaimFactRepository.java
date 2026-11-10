package com.healthclaim.claim.repository;

import com.healthclaim.claim.domain.ClaimFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimFactRepository extends JpaRepository<ClaimFact, UUID> {
    List<ClaimFact> findByClaimId(UUID claimId);

    Optional<ClaimFact> findByClaimIdAndFactKey(UUID claimId, String factKey);
}
