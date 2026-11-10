package com.healthclaim.claim.service;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.dto.ClaimResponse;
import com.healthclaim.claim.dto.CreateClaimRequest;
import com.healthclaim.claim.dto.PatientRequest;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.common.response.PagedResponse;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.patient.repository.PatientRepository;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.exception.PolicyNotFoundException;
import com.healthclaim.policy.repository.PolicyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Claim use-cases. All work here is local database access in short transactions;
 * AI analysis is a separate, later concern (decision orchestration phase).
 */
@Service
public class ClaimService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final ClaimRepository claimRepository;
    private final PolicyRepository policyRepository;
    private final PatientRepository patientRepository;

    public ClaimService(ClaimRepository claimRepository,
                        PolicyRepository policyRepository,
                        PatientRepository patientRepository) {
        this.claimRepository = claimRepository;
        this.policyRepository = policyRepository;
        this.patientRepository = patientRepository;
    }

    @Transactional
    public ClaimResponse create(CreateClaimRequest request) {
        Policy policy = policyRepository.findByPolicyNumber(request.policyNumber())
                .orElseThrow(() -> new PolicyNotFoundException(request.policyNumber()));

        Patient patient = resolvePatient(request.patient());

        Claim claim = new Claim();
        claim.setClaimNumber(generateClaimNumber());
        claim.setPolicy(policy);
        claim.setPatient(patient);
        claim.setProvider(request.provider());
        claim.setDiagnosis(request.diagnosis());
        claim.setProcedureName(request.procedureName());
        claim.setTreatment(request.treatment());
        claim.setHospitalizationStart(request.hospitalizationStart());
        claim.setHospitalizationEnd(request.hospitalizationEnd());
        claim.setClaimAmount(request.claimAmount());
        claim.setCurrency(StringUtils.hasText(request.currency()) ? request.currency() : DEFAULT_CURRENCY);
        claim.setStatus(ClaimStatus.SUBMITTED);
        claim.setSubmittedAt(Instant.now());

        return ClaimMapper.toResponse(claimRepository.save(claim));
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(UUID claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
        return ClaimMapper.toResponse(claim);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ClaimResponse> list(ClaimStatus status, String policyNumber, Pageable pageable) {
        Page<Claim> page;
        if (status != null) {
            page = claimRepository.findByStatus(status, pageable);
        } else if (StringUtils.hasText(policyNumber)) {
            Policy policy = policyRepository.findByPolicyNumber(policyNumber)
                    .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
            page = claimRepository.findByPolicyId(policy.getId(), pageable);
        } else {
            page = claimRepository.findAll(pageable);
        }
        return PagedResponse.from(page.map(ClaimMapper::toResponse));
    }

    private Patient resolvePatient(PatientRequest pr) {
        return patientRepository.findByReference(pr.reference()).orElseGet(() -> {
            Patient patient = new Patient();
            patient.setReference(pr.reference());
            patient.setDisplayName(pr.displayName());
            patient.setDateOfBirth(pr.dateOfBirth());
            patient.setGender(pr.gender());
            return patientRepository.save(patient);
        });
    }

    private static String generateClaimNumber() {
        return "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
