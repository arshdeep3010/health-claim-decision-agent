package com.healthclaim.audit.service;

import com.healthclaim.audit.domain.AuditLog;
import com.healthclaim.audit.dto.AuditLogResponse;
import com.healthclaim.audit.repository.AuditLogRepository;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.repository.ClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side access to a claim's audit trail.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;
    private final ClaimRepository claimRepository;

    public AuditQueryService(AuditLogRepository auditLogRepository, ClaimRepository claimRepository) {
        this.auditLogRepository = auditLogRepository;
        this.claimRepository = claimRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getTrail(UUID claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }
        return auditLogRepository.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .map(AuditQueryService::toResponse)
                .toList();
    }

    private static AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(
                a.getId(), a.getClaimId(),
                a.getEventType() != null ? a.getEventType().name() : null,
                a.getModelProvider(), a.getModelName(), a.getModelVersion(), a.getPromptVersion(),
                a.getFinalDecision() != null ? a.getFinalDecision().name() : null,
                a.getLatencyMs(), a.getHumanOverride(), a.getTraceId(), a.getDetail(), a.getCreatedAt());
    }
}
