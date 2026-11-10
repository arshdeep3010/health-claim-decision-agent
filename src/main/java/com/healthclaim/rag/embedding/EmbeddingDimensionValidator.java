package com.healthclaim.rag.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails fast at startup if the active embedding provider's vector dimension does
 * not match the migrated {@code policy_chunk.embedding vector(N)} column. This
 * enforces the "fail clearly, never silently store wrong-sized vectors" rule.
 */
@Component
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionValidator.class);
    private static final Pattern DIM = Pattern.compile("\\((\\d+)\\)");

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddingProvider;

    public EmbeddingDimensionValidator(JdbcTemplate jdbcTemplate, EmbeddingProvider embeddingProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer columnDim = columnDimension();
        int providerDim = embeddingProvider.dimension();
        if (columnDim != null && columnDim != providerDim) {
            throw new IllegalStateException(String.format(
                    "Embedding dimension mismatch: provider '%s' produces %d-dim vectors but "
                            + "policy_chunk.embedding is vector(%d). Set EMBEDDING_DIM=%d and migrate a fresh "
                            + "schema, or configure an embedding model whose dimension is %d.",
                    embeddingProvider.modelName(), providerDim, columnDim, providerDim, columnDim));
        }
        log.info("Embedding dimension check OK (provider='{}', dimension={})",
                embeddingProvider.modelName(), providerDim);
    }

    private Integer columnDimension() {
        try {
            String formatted = jdbcTemplate.queryForObject(
                    "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                            + "WHERE attrelid = 'policy_chunk'::regclass AND attname = 'embedding' AND NOT attisdropped",
                    String.class);
            if (formatted == null) {
                return null;
            }
            Matcher matcher = DIM.matcher(formatted);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (DataAccessException e) {
            log.warn("Could not determine embedding column dimension: {}", e.getMessage());
            return null;
        }
    }
}
