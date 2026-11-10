package com.healthclaim.audit.controller;

import com.healthclaim.audit.dto.AuditLogResponse;
import com.healthclaim.audit.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}")
@Tag(name = "Audit", description = "Append-only audit trail for a claim")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/audit")
    @Operation(summary = "Get the audit trail for a claim",
            description = "Append-only records of analysis and review events. Contains operational "
                    + "metadata only (no secrets or raw sensitive data).")
    public List<AuditLogResponse> audit(@PathVariable UUID claimId) {
        return auditQueryService.getTrail(claimId);
    }
}
