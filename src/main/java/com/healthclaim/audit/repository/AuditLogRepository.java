package com.healthclaim.audit.repository;

import com.healthclaim.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByClaimIdOrderByCreatedAtAsc(UUID claimId);

    Page<AuditLog> findByClaimId(UUID claimId, Pageable pageable);
}
