package com.healthclaim.decision.controller;

import com.healthclaim.common.exception.ApiException;
import com.healthclaim.decision.config.AnalysisStreamConfig;
import com.healthclaim.decision.dto.DecisionResponse;
import com.healthclaim.decision.dto.EvidenceResponse;
import com.healthclaim.decision.service.DecisionOrchestrator;
import com.healthclaim.decision.service.DecisionQueryService;
import com.healthclaim.decision.stream.AnalysisStage;
import com.healthclaim.decision.stream.StageEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim analysis and decision endpoints. Analysis is synchronous for the MVP;
 * an optional {@code Idempotency-Key} makes repeated analyze calls safe.
 */
@RestController
@RequestMapping("/api/v1/claims/{claimId}")
@Tag(name = "Decisions", description = "Analyze claims and retrieve decisions and evidence")
public class DecisionController {

    private static final Logger log = LoggerFactory.getLogger(DecisionController.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final DecisionOrchestrator orchestrator;
    private final DecisionQueryService queryService;
    private final TaskExecutor streamExecutor;

    public DecisionController(DecisionOrchestrator orchestrator, DecisionQueryService queryService,
                              @Qualifier(AnalysisStreamConfig.EXECUTOR) TaskExecutor streamExecutor) {
        this.orchestrator = orchestrator;
        this.queryService = queryService;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping("/analyze")
    @Operation(summary = "Analyze a claim",
            description = "Runs deterministic rules + AI-assisted judgment and produces an auditable "
                    + "APPROVE / REJECT / HUMAN_REVIEW decision. RAG or AI failure routes to HUMAN_REVIEW.")
    public DecisionResponse analyze(@PathVariable UUID claimId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return orchestrator.analyze(claimId, idempotencyKey);
    }

    /**
     * Same analysis as {@link #analyze}, streamed as Server-Sent Events so a client can
     * show each stage as it actually happens. Events, in order:
     * {@code stage} (one per stage transition, {@link StageEvent}), then exactly one of
     * {@code decision} ({@link DecisionResponse}) or {@code error} ({status, error, message}).
     * Uses POST (it creates a decision) and is consumed with fetch() streaming,
     * which, unlike EventSource, can send the Authorization header.
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Analyze a claim, streaming each pipeline stage as Server-Sent Events")
    public SseEmitter analyzeStream(@PathVariable UUID claimId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    Authentication authentication) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // The request already passed the JWT filter and role check to get here.
        send(emitter, "stage", StageEvent.completed(AnalysisStage.AUTH, 0L, Map.of(
                "principal", authentication != null ? authentication.getName() : "anonymous",
                "roles", authentication == null ? List.of() : authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).map(a -> a.replaceFirst("^ROLE_", "")).sorted().toList())));
        try {
            streamExecutor.execute(() -> {
                try {
                    DecisionResponse decision = orchestrator.analyze(claimId, idempotencyKey,
                            event -> send(emitter, "stage", event));
                    send(emitter, "decision", decision);
                } catch (ApiException e) {
                    send(emitter, "error", Map.of("status", e.status().value(), "error", e.errorCode(), "message", e.getMessage()));
                } catch (RuntimeException e) {
                    log.error("Streamed analysis failed for claim {}", claimId, e);
                    send(emitter, "error", Map.of("status", 500, "error", "INTERNAL_ERROR",
                            "message", "An unexpected error occurred. Reference the traceId when contacting support."));
                } finally {
                    emitter.complete();
                }
            });
        } catch (TaskRejectedException e) {
            send(emitter, "error", Map.of("status", 503, "error", "BUSY", "message", "Too many analyses in progress. Retry shortly."));
            emitter.complete();
        }
        return emitter;
    }

    /** Best effort: a client that disconnected must not abort the analysis itself. */
    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping '{}' event; client disconnected: {}", name, e.getMessage());
        }
    }

    @GetMapping("/decision")
    @Operation(summary = "Get the current decision for a claim")
    public DecisionResponse getDecision(@PathVariable UUID claimId) {
        return queryService.getDecision(claimId);
    }

    @GetMapping("/evidence")
    @Operation(summary = "Get the policy evidence that supported the decision")
    public List<EvidenceResponse> getEvidence(@PathVariable UUID claimId) {
        return queryService.getEvidence(claimId);
    }
}
