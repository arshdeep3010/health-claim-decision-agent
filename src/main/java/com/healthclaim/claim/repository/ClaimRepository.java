package com.healthclaim.claim.repository;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByClaimNumber(String claimNumber);

    Page<Claim> findByStatus(ClaimStatus status, Pageable pageable);

    Page<Claim> findByPolicyId(UUID policyId, Pageable pageable);
}
