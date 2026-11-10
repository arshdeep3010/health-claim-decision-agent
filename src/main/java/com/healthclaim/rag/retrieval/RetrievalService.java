package com.healthclaim.rag.retrieval;

import com.healthclaim.config.RagProperties;
import com.healthclaim.rag.embedding.EmbeddingProvider;
import com.healthclaim.rag.embedding.VectorLiterals;
import com.healthclaim.rag.evidence.EvidenceResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Semantic retrieval over ingested policy chunks using pgvector cosine distance.
 * Returns evidence ordered by similarity, each traceable to its source chunk.
 */
@Service
public class RetrievalService {

    private static final int MAX_TOP_K = 50;

    private final EmbeddingProvider embeddingProvider;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;

    public RetrievalService(EmbeddingProvider embeddingProvider,
                            JdbcTemplate jdbcTemplate,
                            RagProperties properties) {
        this.embeddingProvider = embeddingProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** The embedding model behind retrieval (reported in the live analysis stream). */
    public String embeddingModel() {
        return embeddingProvider.modelName();
    }

    public List<EvidenceResult> retrieve(UUID policyId, String query, Integer topK) {
        int limit = (topK != null && topK > 0) ? Math.min(topK, MAX_TOP_K) : properties.topK();
        String queryVector = VectorLiterals.toLiteral(embeddingProvider.embedQuery(query));

        return jdbcTemplate.query(
                "SELECT id, document_id, policy_id, page_number, section, heading, chunk_text, "
                        + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM policy_chunk "
                        + "WHERE policy_id = ? AND embedding IS NOT NULL "
                        + "ORDER BY embedding <=> CAST(? AS vector) "
                        + "LIMIT ?",
                (rs, rowNum) -> new EvidenceResult(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("policy_id", UUID.class),
                        rs.getObject("page_number", Integer.class),
                        rs.getString("section"),
                        rs.getString("heading"),
                        snippet(rs.getString("chunk_text")),
                        rs.getDouble("similarity")),
                queryVector, policyId, queryVector, limit);
    }

    private String snippet(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= properties.maxSnippetLength()
                ? text
                : text.substring(0, properties.maxSnippetLength());
    }
}
