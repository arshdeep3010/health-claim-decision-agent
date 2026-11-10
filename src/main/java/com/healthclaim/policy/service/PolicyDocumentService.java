package com.healthclaim.policy.service;

import com.healthclaim.common.domain.DocumentStatus;
import com.healthclaim.document.service.DocumentIngestionService;
import com.healthclaim.document.storage.DocumentStorageService;
import com.healthclaim.document.validation.UploadedFileValidator;
import com.healthclaim.document.validation.ValidatedUpload;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyDocument;
import com.healthclaim.policy.dto.IngestionResultResponse;
import com.healthclaim.policy.dto.PolicyDocumentResponse;
import com.healthclaim.policy.exception.PolicyNotFoundException;
import com.healthclaim.policy.repository.PolicyDocumentRepository;
import com.healthclaim.policy.repository.PolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload and ingestion use-cases for policy documents. File storage happens
 * outside any DB transaction; ingestion delegates to {@link DocumentIngestionService}.
 */
@Service
public class PolicyDocumentService {

    private final PolicyRepository policyRepository;
    private final PolicyDocumentRepository documentRepository;
    private final DocumentStorageService storage;
    private final UploadedFileValidator validator;
    private final DocumentIngestionService ingestionService;

    public PolicyDocumentService(PolicyRepository policyRepository,
                                 PolicyDocumentRepository documentRepository,
                                 DocumentStorageService storage,
                                 UploadedFileValidator validator,
                                 DocumentIngestionService ingestionService) {
        this.policyRepository = policyRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.validator = validator;
        this.ingestionService = ingestionService;
    }

    public PolicyDocumentResponse upload(UUID policyId, MultipartFile file) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId.toString()));

        ValidatedUpload validated = validator.validate(file);

        // De-duplicate identical content within a policy (avoids re-storing/re-embedding).
        Optional<PolicyDocument> duplicate =
                documentRepository.findByPolicyIdAndSha256(policyId, validated.sha256());
        if (duplicate.isPresent()) {
            return PolicyMapper.toDocumentResponse(duplicate.get(), policyId);
        }

        String storageKey = storage.store(validated.content(), validated.contentType());

        PolicyDocument document = new PolicyDocument();
        document.setPolicy(policy);
        document.setStorageKey(storageKey);
        document.setOriginalFilename(validated.originalFilename());
        document.setContentType(validated.contentType());
        document.setFileSizeBytes(validated.size());
        document.setSha256(validated.sha256());
        document.setStatus(DocumentStatus.UPLOADED);

        return PolicyMapper.toDocumentResponse(documentRepository.save(document), policyId);
    }

    @Transactional(readOnly = true)
    public List<PolicyDocumentResponse> list(UUID policyId) {
        requirePolicy(policyId);
        return documentRepository.findByPolicyId(policyId).stream()
                .map(d -> PolicyMapper.toDocumentResponse(d, policyId))
                .toList();
    }

    public IngestionResultResponse ingest(UUID policyId) {
        return ingest(policyId, false);
    }

    /**
     * Ingests the policy's UPLOADED documents, or with {@code reembed} every document,
     * rebuilding its chunks with the current embedding provider (after a model change).
     */
    public IngestionResultResponse ingest(UUID policyId, boolean reembed) {
        requirePolicy(policyId);
        List<PolicyDocument> pending = reembed
                ? documentRepository.findByPolicyId(policyId)
                : documentRepository.findByPolicyIdAndStatus(policyId, DocumentStatus.UPLOADED);
        int chunks = 0;
        for (PolicyDocument document : pending) {
            chunks += ingestionService.ingestDocument(document.getId());
        }
        return new IngestionResultResponse(policyId, pending.size(), chunks);
    }

    private void requirePolicy(UUID policyId) {
        if (!policyRepository.existsById(policyId)) {
            throw new PolicyNotFoundException(policyId.toString());
        }
    }
}
