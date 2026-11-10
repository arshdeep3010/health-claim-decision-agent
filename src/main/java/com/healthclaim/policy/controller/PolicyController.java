package com.healthclaim.policy.controller;

import com.healthclaim.common.response.PagedResponse;
import com.healthclaim.policy.dto.CreatePolicyRequest;
import com.healthclaim.policy.dto.IngestionResultResponse;
import com.healthclaim.policy.dto.PolicyChunkResponse;
import com.healthclaim.policy.dto.PolicyDocumentResponse;
import com.healthclaim.policy.dto.PolicyResponse;
import com.healthclaim.policy.service.PolicyChunkQueryService;
import com.healthclaim.policy.service.PolicyDocumentService;
import com.healthclaim.policy.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "Manage synthetic policies and ingest their documents")
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyDocumentService documentService;
    private final PolicyChunkQueryService chunkQueryService;

    public PolicyController(PolicyService policyService, PolicyDocumentService documentService,
                            PolicyChunkQueryService chunkQueryService) {
        this.policyService = policyService;
        this.documentService = documentService;
        this.chunkQueryService = chunkQueryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a policy")
    public PolicyResponse create(@Valid @RequestBody CreatePolicyRequest request) {
        return policyService.create(request);
    }

    @GetMapping("/{policyId}")
    @Operation(summary = "Get a policy by id")
    public PolicyResponse get(@PathVariable UUID policyId) {
        return policyService.get(policyId);
    }

    @GetMapping
    @Operation(summary = "List policies")
    public PagedResponse<PolicyResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return policyService.list(pageable);
    }

    @PostMapping(path = "/{policyId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a policy document (PDF only)")
    public PolicyDocumentResponse upload(@PathVariable UUID policyId,
                                         @RequestPart("file") MultipartFile file) {
        return documentService.upload(policyId, file);
    }

    @GetMapping("/{policyId}/documents")
    @Operation(summary = "List documents for a policy")
    public List<PolicyDocumentResponse> listDocuments(@PathVariable UUID policyId) {
        return documentService.list(policyId);
    }

    @GetMapping("/{policyId}/chunks")
    @Operation(summary = "List the policy's indexed wording (RAG chunks)",
            description = "The text the decision layer can retrieve as evidence, with page, section and the "
                    + "embedding model that indexed it. Vectors are not returned, only their dimension.")
    public List<PolicyChunkResponse> listChunks(@PathVariable UUID policyId) {
        return chunkQueryService.list(policyId);
    }

    @PostMapping("/{policyId}/ingest")
    @Operation(summary = "Ingest uploaded documents into retrievable chunks",
            description = "Parses UPLOADED PDFs for the policy, chunks and embeds them, and stores the chunks. "
                    + "With reembed=true every document is reprocessed, e.g. after changing the embedding model.")
    public IngestionResultResponse ingest(@PathVariable UUID policyId,
                                          @RequestParam(defaultValue = "false") boolean reembed) {
        return documentService.ingest(policyId, reembed);
    }
}
