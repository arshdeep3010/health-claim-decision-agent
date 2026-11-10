package com.healthclaim.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimFact;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.domain.FactSource;
import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;
import com.healthclaim.claim.repository.ClaimFactRepository;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.patient.repository.PatientRepository;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.policy.domain.PolicyType;
import com.healthclaim.policy.repository.PolicyRepository;
import com.healthclaim.synthetic.model.SyntheticDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Seeds the synthetic policies, patients, claims and pre-extracted facts into the
 * database for local development/demo (dev profile only). Idempotent: existing
 * policies/claims (by number) are left untouched. Facts are pre-populated so the
 * decision pipeline is demonstrable offline (no LLM needed). SYNTHETIC DATA ONLY.
 */
@Component
@Profile({"dev", "demo"})
public class SyntheticDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataSeeder.class);

    private final PolicyRepository policyRepository;
    private final PatientRepository patientRepository;
    private final ClaimRepository claimRepository;
    private final ClaimFactRepository claimFactRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public SyntheticDataSeeder(PolicyRepository policyRepository,
                               PatientRepository patientRepository,
                               ClaimRepository claimRepository,
                               ClaimFactRepository claimFactRepository,
                               ObjectMapper objectMapper,
                               @Value("${app.synthetic.seed-enabled:true}") boolean enabled) {
        this.policyRepository = policyRepository;
        this.patientRepository = patientRepository;
        this.claimRepository = claimRepository;
        this.claimFactRepository = claimFactRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            seed();
        } catch (Exception e) {
            // Never fail startup because of demo data.
            log.warn("Synthetic data seeding skipped due to error: {}", e.getMessage());
        }
    }

    @Transactional
    public void seed() {
        SyntheticDataset dataset = SyntheticDataLoader.load(objectMapper);
        int policies = 0;
        int claims = 0;

        for (SyntheticDataset.SyntheticPolicy p : dataset.policies()) {
            if (policyRepository.findByPolicyNumber(p.policyNumber()).isPresent()) {
                continue;
            }
            Policy policy = new Policy();
            policy.setPolicyNumber(p.policyNumber());
            policy.setPolicyType(PolicyType.valueOf(p.policyType()));
            policy.setStatus(PolicyStatus.valueOf(p.status()));
            policy.setEffectiveDate(p.effectiveDate());
            policy.setExpiryDate(p.expiryDate());
            policy.setCoverageLimit(p.coverageLimit());
            policy.setDeductible(p.deductible());
            policy.setCopayPercentage(p.copayPercentage());
            policy.setCurrency(p.currency());
            policy.setExclusions(toJson(p.exclusions()));
            policy.setWaitingPeriods(toJson(p.waitingPeriods()));
            policyRepository.save(policy);
            policies++;
        }

        for (SyntheticDataset.SyntheticClaim c : dataset.claims()) {
            if (claimRepository.findByClaimNumber(c.claimNumber()).isPresent()) {
                continue;
            }
            Policy policy = policyRepository.findByPolicyNumber(c.policyNumber()).orElse(null);
            if (policy == null) {
                continue;
            }
            Patient patient = patientRepository.findByReference(c.patientReference()).orElseGet(() -> {
                Patient np = new Patient();
                np.setReference(c.patientReference());
                np.setDisplayName(c.patientName());
                return patientRepository.save(np);
            });

            Claim claim = new Claim();
            claim.setClaimNumber(c.claimNumber());
            claim.setPolicy(policy);
            claim.setPatient(patient);
            claim.setProvider(c.provider());
            claim.setDiagnosis(c.diagnosis());
            claim.setProcedureName(c.procedureName());
            claim.setTreatment(c.treatment());
            claim.setHospitalizationStart(c.hospitalizationStart());
            claim.setHospitalizationEnd(c.hospitalizationEnd());
            claim.setClaimAmount(c.claimAmount());
            claim.setCurrency(c.currency());
            claim.setStatus(ClaimStatus.SUBMITTED);
            claim.setSubmittedAt(Instant.now());
            Claim savedClaim = claimRepository.save(claim);
            claims++;

            if (c.facts() != null) {
                c.facts().forEach((key, fv) -> FactKey.fromWire(key).ifPresent(factKey -> {
                    ClaimFact fact = new ClaimFact();
                    fact.setClaim(savedClaim);
                    fact.setFactKey(factKey.wire());
                    fact.setFactValue(fv.value());
                    fact.setValueType(factKey.valueType());
                    fact.setStatus(parseStatus(fv.status()));
                    fact.setSource(FactSource.PROVIDED);
                    claimFactRepository.save(fact);
                }));
            }
        }

        log.info("Synthetic data seeded: {} new policies, {} new claims (dev profile). SYNTHETIC DATA ONLY.",
                policies, claims);
    }

    private static FactStatus parseStatus(String status) {
        try {
            return FactStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return FactStatus.UNKNOWN;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
