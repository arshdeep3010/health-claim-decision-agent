package com.healthclaim.document.service;

import com.healthclaim.common.domain.DocumentStatus;
import com.healthclaim.document.chunker.Chunk;
import com.healthclaim.document.exception.DocumentNotFoundException;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyChunk;
import com.healthclaim.policy.domain.PolicyDocument;
import com.healthclaim.policy.repository.PolicyChunkRepository;
import com.healthclaim.policy.repository.PolicyDocumentRepository;
import com.healthclaim.policy.repository.PolicyRepository;
import com.healthclaim.rag.embedding.VectorLiterals;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional database work for ingestion, isolated in its own bean so the
 * orchestrating service runs PDF parsing and embedding OUTSIDE any DB
 * transaction (we never hold a connection during CPU-bound or network work).
 */
@Component
public class IngestionStore {

    private final PolicyDocumentRepository documentRepository;
    private final PolicyChunkRepository chunkRepository;
    private final PolicyRepository policyRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public IngestionStore(PolicyDocumentRepository documentRepository,
                          PolicyChunkRepository chunkRepository,
                          PolicyRepository policyRepository) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.policyRepository = policyRepository;
    }

    @Transactional(readOnly = true)
    public IngestRef loadRef(UUID documentId) {
        PolicyDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return new IngestRef(doc.getStorageKey(), doc.getPolicy().getId());
    }

    @Transactional
    public int replaceChunks(UUID documentId, UUID policyId, int pageCount,
                             List<Chunk> chunks, List<float[]> embeddings, String embeddingModel) {
        // Bulk-delete existing chunks first and execute immediately, so re-ingestion
        // never collides with the (document_id, chunk_index) unique constraint.
        entityManager.createQuery("delete from PolicyChunk c where c.document.id = :docId")
                .setParameter("docId", documentId)
                .executeUpdate();

        PolicyDocument documentRef = documentRepository.getReferenceById(documentId);
        Policy policyRef = policyRepository.getReferenceById(policyId);

        List<PolicyChunk> saved = new ArrayList<>(chunks.size());
        int index = 0;
        for (Chunk chunk : chunks) {
            PolicyChunk entity = new PolicyChunk();
            entity.setDocument(documentRef);
            entity.setPolicy(policyRef);
            entity.setChunkIndex(index++);
            entity.setPageNumber(chunk.pageNumber());
            entity.setSection(chunk.section());
            entity.setHeading(chunk.heading());
            entity.setChunkText(chunk.text());
            entity.setTokenCount(Math.max(1, chunk.text().length() / 4));
            saved.add(chunkRepository.save(entity));
        }

        // Flush inserts before setting vectors via native SQL (embedding is not JPA-mapped).
        entityManager.flush();
        for (int i = 0; i < saved.size(); i++) {
            if (i < embeddings.size()) {
                entityManager.createNativeQuery(
                                "UPDATE policy_chunk SET embedding = CAST(:vec AS vector), embedding_model = :model "
                                        + "WHERE id = CAST(:id AS uuid)")
                        .setParameter("vec", VectorLiterals.toLiteral(embeddings.get(i)))
                        .setParameter("model", embeddingModel)
                        .setParameter("id", saved.get(i).getId().toString())
                        .executeUpdate();
            }
        }

        PolicyDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        doc.setStatus(DocumentStatus.INGESTED);
        doc.setPageCount(pageCount);
        documentRepository.save(doc);

        return chunks.size();
    }
}
