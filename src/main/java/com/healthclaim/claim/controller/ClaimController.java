package com.healthclaim.claim.controller;

import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.dto.ClaimExtractionResponse;
import com.healthclaim.claim.dto.ClaimFactResponse;
import com.healthclaim.claim.dto.ClaimResponse;
import com.healthclaim.claim.dto.CreateClaimRequest;
import com.healthclaim.claim.extraction.ClaimExtractionService;
import com.healthclaim.claim.service.ClaimService;
import com.healthclaim.common.response.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims", description = "Submit and retrieve synthetic health-insurance claims")
public class ClaimController {

    private final ClaimService claimService;
    private final ClaimExtractionService extractionService;

    public ClaimController(ClaimService claimService, ClaimExtractionService extractionService) {
        this.claimService = claimService;
        this.extractionService = extractionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a new claim",
            description = "Creates a claim in SUBMITTED status. The referenced policy must exist.")
    public ClaimResponse create(@Valid @RequestBody CreateClaimRequest request) {
        return claimService.create(request);
    }

    @GetMapping("/{claimId}")
    @Operation(summary = "Get a claim by id")
    public ClaimResponse get(@PathVariable UUID claimId) {
        return claimService.get(claimId);
    }

    @GetMapping
    @Operation(summary = "List claims",
            description = "Optionally filter by status or policyNumber. Paginated and sorted by createdAt desc by default.")
    public PagedResponse<ClaimResponse> list(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String policyNumber,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return claimService.list(status, policyNumber, pageable);
    }

    @PostMapping("/{claimId}/extract")
    @Operation(summary = "Extract structured facts from a claim using the LLM",
            description = "Runs schema-constrained extraction and persists validated ClaimFacts. "
                    + "Each fact carries a KNOWN/UNKNOWN/CONFLICTING status; missing info is never fabricated.")
    public ClaimExtractionResponse extract(@PathVariable UUID claimId) {
        return extractionService.extract(claimId);
    }

    @GetMapping("/{claimId}/facts")
    @Operation(summary = "List the extracted facts for a claim")
    public List<ClaimFactResponse> facts(@PathVariable UUID claimId) {
        return extractionService.getFacts(claimId);
    }
}
