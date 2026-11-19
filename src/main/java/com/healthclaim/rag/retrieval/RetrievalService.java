package com.healthclaim.rag.retrieval;

import com.healthclaim.config.RagProperties;
import com.healthclaim.rag.embedding.EmbeddingProvider;
import com.healthclaim.rag.embedding.VectorLiterals;
import com.healthclaim.rag.evidence.EvidenceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Retrieval over ingested policy chunks.
 *
 * <ol>
 *   <li><b>Vector search</b> (pgvector cosine distance) is the normal path.</li>
 *   <li>Query vectors are <b>cached</b> in Postgres, so analysing the same claim again
 *       never calls the embedding API.</li>
 *   <li>If the embedding API fails (the free hosted endpoint returns 503 when
 *       overloaded), retrieval falls back to <b>keyword search</b> (Postgres full-text
 *       ranking) over the same chunks, and says so. Only if that finds nothing does the
 *       original error propagate, which routes the claim to human review.</li>
 * </ol>
 * Every result stays traceable to its source chunk.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int MAX_TOP_K = 50;
    private static final int MIN_TERM_LENGTH = 3;

    /** How the evidence was found. */
    public enum Method { VECTOR, CACHED_VECTOR, KEYWORD }

    /** Evidence plus how it was retrieved (reported in the live analysis stream). */
    public record Retrieval(List<EvidenceResult> evidence, Method method) {
    }

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
        return search(policyId, query, topK).evidence();
    }

    public Retrieval search(UUID policyId, String query, Integer topK) {
        int limit = (topK != null && topK > 0) ? Math.min(topK, MAX_TOP_K) : properties.topK();
        String hash = sha256(query);

        String cached = cachedQueryVector(hash);
        if (cached != null) {
            return new Retrieval(vectorSearch(policyId, cached, limit), Method.CACHED_VECTOR);
        }

        String queryVector;
        try {
            queryVector = VectorLiterals.toLiteral(embeddingProvider.embedQuery(query));
        } catch (RuntimeException e) {
            List<EvidenceResult> keyword = keywordSearch(policyId, query, limit);
            if (keyword.isEmpty()) {
                throw e;
            }
            log.warn("Embedding provider unavailable ({}); used keyword retrieval instead", e.getMessage());
            return new Retrieval(keyword, Method.KEYWORD);
        }
        cacheQueryVector(hash, queryVector);
        return new Retrieval(vectorSearch(policyId, queryVector, limit), Method.VECTOR);
    }

    private List<EvidenceResult> vectorSearch(UUID policyId, String queryVector, int limit) {
        return jdbcTemplate.query(
                "SELECT id, document_id, policy_id, page_number, section, heading, chunk_text, "
                        + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM policy_chunk "
                        + "WHERE policy_id = ? AND embedding IS NOT NULL "
                        + "ORDER BY embedding <=> CAST(? AS vector) "
                        + "LIMIT ?",
                evidenceMapper(), queryVector, policyId, queryVector, limit);
    }

    /**
     * Full-text ranking: any query term may match (OR), ranked by cover density and
     * normalised to 0..1 ({@code rank / (rank + 1)}).
     */
    List<EvidenceResult> keywordSearch(UUID policyId, String query, int limit) {
        String tsQuery = toOrTsQuery(query);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                    "SELECT id, document_id, policy_id, page_number, section, heading, chunk_text, "
                            + "ts_rank_cd(to_tsvector('english', chunk_text), q, 32) AS similarity "
                            + "FROM policy_chunk, to_tsquery('english', ?) q "
                            + "WHERE policy_id = ? AND to_tsvector('english', chunk_text) @@ q "
                            + "ORDER BY similarity DESC "
                            + "LIMIT ?",
                    evidenceMapper(), tsQuery, policyId, limit);
        } catch (DataAccessException e) {
            log.warn("Keyword retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** {@code "Gallstones, Laparoscopic cholecystectomy"} -> {@code "gallstones | laparoscopic | cholecystectomy"}. */
    static String toOrTsQuery(String query) {
        if (query == null) {
            return "";
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= MIN_TERM_LENGTH) {
                terms.add(token);
            }
        }
        return String.join(" | ", terms);
    }

    private String cachedQueryVector(String hash) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT embedding::text FROM query_embedding_cache WHERE model = ? AND query_hash = ?",
                    String.class, embeddingProvider.modelName(), hash);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            log.debug("Query-vector cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void cacheQueryVector(String hash, String queryVector) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO query_embedding_cache (model, query_hash, embedding) "
                            + "VALUES (?, ?, CAST(? AS vector)) ON CONFLICT DO NOTHING",
                    embeddingProvider.modelName(), hash, queryVector);
        } catch (DataAccessException e) {
            log.debug("Query-vector cache write failed: {}", e.getMessage());
        }
    }

    private RowMapper<EvidenceResult> evidenceMapper() {
        return (rs, rowNum) -> new EvidenceResult(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("policy_id", UUID.class),
                rs.getObject("page_number", Integer.class),
                rs.getString("section"),
                rs.getString("heading"),
                snippet(rs.getString("chunk_text")),
                rs.getDouble("similarity"));
    }

    private String snippet(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= properties.maxSnippetLength()
                ? text
                : text.substring(0, properties.maxSnippetLength());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
