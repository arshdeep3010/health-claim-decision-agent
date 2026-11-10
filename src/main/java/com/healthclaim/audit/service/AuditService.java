package com.healthclaim.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.audit.domain.AuditEventType;
import com.healthclaim.audit.domain.AuditLog;
import com.healthclaim.audit.repository.AuditLogRepository;
import com.healthclaim.common.web.CorrelationIdFilter;
import com.healthclaim.decision.domain.DecisionType;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes append-only audit records. Detail payloads contain ids and decisions
 * only — never secrets or raw sensitive data. Expanded in the observability phase
 * to cover the full analysis pipeline.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public void recordHumanReview(UUID claimId, UUID reviewId, String reviewerId,
                                  DecisionType previousAiDecision, DecisionType finalDecision, boolean override) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewId", reviewId != null ? reviewId.toString() : null);
        detail.put("reviewerId", reviewerId);
        detail.put("previousAiDecision", previousAiDecision != null ? previousAiDecision.name() : null);

        AuditLog log = baseLog(claimId, override ? AuditEventType.HUMAN_OVERRIDE : AuditEventType.HUMAN_REVIEW_SUBMITTED);
        log.setFinalDecision(finalDecision);
        log.setHumanOverride(override);
        log.setDetail(toJson(detail));
        auditLogRepository.save(log);
    }

    /**
     * Records one auditable summary of an analysis run (§19). Stores operational
     * metadata and reason codes only — never raw facts, documents, or secrets.
     */
    public void recordAnalysis(UUID claimId, DecisionType finalDecision, String modelProvider,
                               String modelName, String promptVersion, Long latencyMs,
                               String ruleOutcome, java.util.List<String> reasonCodes,
                               int evidenceCount, boolean ragFailed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleOutcome", ruleOutcome);
        detail.put("reasonCodes", reasonCodes);
        detail.put("evidenceCount", evidenceCount);
        detail.put("ragFailed", ragFailed);

        AuditLog log = baseLog(claimId, AuditEventType.AI_DECISION);
        log.setFinalDecision(finalDecision);
        log.setModelProvider(modelProvider);
        log.setModelName(modelName);
        log.setPromptVersion(promptVersion);
        log.setLatencyMs(latencyMs);
        log.setDetail(toJson(detail));
        auditLogRepository.save(log);
    }

    public void recordDecision(UUID claimId, AuditEventType eventType, DecisionType finalDecision,
                               String modelProvider, String modelName, String promptVersion,
                               Long latencyMs, Map<String, Object> detail) {
        AuditLog log = baseLog(claimId, eventType);
        log.setFinalDecision(finalDecision);
        log.setModelProvider(modelProvider);
        log.setModelName(modelName);
        log.setPromptVersion(promptVersion);
        log.setLatencyMs(latencyMs);
        log.setDetail(toJson(detail));
        auditLogRepository.save(log);
    }

    private AuditLog baseLog(UUID claimId, AuditEventType eventType) {
        AuditLog log = new AuditLog();
        log.setClaimId(claimId);
        log.setEventType(eventType);
        log.setTraceId(MDC.get(CorrelationIdFilter.TRACE_ID));
        return log;
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }
}
