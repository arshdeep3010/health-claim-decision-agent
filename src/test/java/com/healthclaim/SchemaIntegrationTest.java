package com.healthclaim;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.common.domain.DocumentStatus;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.patient.repository.PatientRepository;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyChunk;
import com.healthclaim.policy.domain.PolicyDocument;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.policy.domain.PolicyType;
import com.healthclaim.policy.repository.PolicyChunkRepository;
import com.healthclaim.policy.repository.PolicyDocumentRepository;
import com.healthclaim.policy.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving: Flyway migration applies, entities persist and read
 * back (so Hibernate validate matches the schema), and the pgvector column +
 * cosine distance operator work via native SQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SchemaIntegrationTest {

    private static final int DIM = 384; // must match spring.flyway.placeholders.embeddingDim in tests

    @Autowired
    private PatientRepository patients;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private PolicyDocumentRepository documents;
    @Autowired
    private PolicyChunkRepository chunks;
    @Autowired
    private ClaimRepository claims;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void entitiesPersistAndSchemaValidates() {
        Policy policy = persistPolicyGraph("PAT-1", "POL-1");

        Claim claim = new Claim();
        claim.setClaimNumber("CLM-1");
        claim.setPolicy(policy);
        claim.setPatient(policy.getHolder());
        claim.setClaimAmount(new BigDecimal("145000.00"));
        claim.setCurrency("INR");
        claim.setStatus(ClaimStatus.SUBMITTED);
        claims.save(claim);

        assertThat(patients.findByReference("PAT-1")).isPresent();
        assertThat(policies.findByPolicyNumber("POL-1")).isPresent();
        assertThat(claims.findByClaimNumber("CLM-1")).isPresent();
        assertThat(chunks.findByPolicyId(policy.getId())).hasSize(1);
    }

    @Test
    void pgvectorCosineSearchWorks() {
        Policy policy = persistPolicyGraph("PAT-2", "POL-2");
        PolicyChunk chunk = chunks.findByPolicyId(policy.getId()).get(0);

        jdbc.update("UPDATE policy_chunk SET embedding = CAST(? AS vector) WHERE id = ?",
                vector(DIM, 0), chunk.getId());

        String nearestId = jdbc.queryForObject(
                "SELECT id::text FROM policy_chunk WHERE embedding IS NOT NULL "
                        + "ORDER BY embedding <=> CAST(? AS vector) LIMIT 1",
                String.class, vector(DIM, 0));

        assertThat(nearestId).isEqualTo(chunk.getId().toString());
    }

    /** Creates patient -> policy -> document -> one chunk, and returns the managed policy. */
    private Policy persistPolicyGraph(String patientRef, String policyNumber) {
        Patient patient = new Patient();
        patient.setReference(patientRef);
        patient.setDisplayName("Synthetic Patient");
        patient.setDateOfBirth(LocalDate.of(1985, 5, 20));
        patients.save(patient);

        Policy policy = new Policy();
        policy.setPolicyNumber(policyNumber);
        policy.setPolicyType(PolicyType.INDIVIDUAL);
        policy.setHolder(patient);
        policy.setEffectiveDate(LocalDate.now().minusYears(1));
        policy.setExpiryDate(LocalDate.now().plusYears(1));
        policy.setCoverageLimit(new BigDecimal("500000.00"));
        policy.setDeductible(new BigDecimal("5000.00"));
        policy.setCopayPercentage(new BigDecimal("10.00"));
        policy.setCurrency("INR");
        policy.setStatus(PolicyStatus.ACTIVE);
        policies.save(policy);

        PolicyDocument document = new PolicyDocument();
        document.setPolicy(policy);
        document.setStorageKey(UUID.randomUUID().toString());
        document.setStatus(DocumentStatus.INGESTED);
        documents.save(document);

        PolicyChunk chunk = new PolicyChunk();
        chunk.setDocument(document);
        chunk.setPolicy(policy);
        chunk.setChunkIndex(0);
        chunk.setChunkText("Covered: inpatient hospitalization for gallstone removal.");
        chunks.save(chunk);

        return policy;
    }

    /** Builds a pgvector literal like "[0,1,0,...]" of the given dimension. */
    private static String vector(int dim, int hotIndex) {
        StringBuilder sb = new StringBuilder(dim * 2 + 2).append('[');
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == hotIndex ? "1" : "0");
        }
        return sb.append(']').toString();
    }
}
