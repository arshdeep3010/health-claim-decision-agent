package com.healthclaim.policy.service;

import com.healthclaim.policy.dto.PolicyChunkResponse;
import com.healthclaim.policy.exception.PolicyNotFoundException;
import com.healthclaim.policy.repository.PolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of a policy's indexed wording. Uses native SQL because the
 * embedding column is deliberately unmapped by JPA.
 */
@Service
public class PolicyChunkQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final PolicyRepository policyRepository;

    public PolicyChunkQueryService(JdbcTemplate jdbcTemplate, PolicyRepository policyRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.policyRepository = policyRepository;
    }

    public List<PolicyChunkResponse> list(UUID policyId) {
        if (!policyRepository.existsById(policyId)) {
            throw new PolicyNotFoundException(policyId.toString());
        }
        return jdbcTemplate.query(
                "SELECT id, document_id, chunk_index, page_number, section, heading, chunk_text, token_count, "
                        + "embedding_model, CASE WHEN embedding IS NULL THEN NULL ELSE vector_dims(embedding) END AS dims "
                        + "FROM policy_chunk WHERE policy_id = ? ORDER BY document_id, chunk_index",
                (rs, i) -> new PolicyChunkResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getInt("chunk_index"),
                        (Integer) rs.getObject("page_number"),
                        rs.getString("section"),
                        rs.getString("heading"),
                        rs.getString("chunk_text"),
                        (Integer) rs.getObject("token_count"),
                        rs.getString("embedding_model"),
                        (Integer) rs.getObject("dims")),
                policyId);
    }
}
