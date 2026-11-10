package com.healthclaim.policy.repository;

import com.healthclaim.common.domain.DocumentStatus;
import com.healthclaim.policy.domain.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {
    List<PolicyDocument> findByPolicyId(UUID policyId);

    List<PolicyDocument> findByPolicyIdAndStatus(UUID policyId, DocumentStatus status);

    Optional<PolicyDocument> findByPolicyIdAndSha256(UUID policyId, String sha256);
}
