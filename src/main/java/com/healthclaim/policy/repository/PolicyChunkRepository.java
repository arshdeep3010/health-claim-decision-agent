package com.healthclaim.policy.repository;

import com.healthclaim.policy.domain.PolicyChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, UUID> {
    List<PolicyChunk> findByPolicyId(UUID policyId);

    List<PolicyChunk> findByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
