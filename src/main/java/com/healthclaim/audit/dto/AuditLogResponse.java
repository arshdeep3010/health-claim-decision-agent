package com.healthclaim.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID claimId,
        String eventType,
        String modelProvider,
        String modelName,
        String modelVersion,
        String promptVersion,
        String finalDecision,
        Long latencyMs,
        Boolean humanOverride,
        String traceId,
        String detail,
        Instant createdAt
) {
}
