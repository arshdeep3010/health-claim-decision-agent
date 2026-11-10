package com.healthclaim.policy.service;

import com.healthclaim.common.response.PagedResponse;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.patient.repository.PatientRepository;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.policy.dto.CreatePolicyRequest;
import com.healthclaim.policy.dto.PolicyResponse;
import com.healthclaim.policy.exception.PolicyAlreadyExistsException;
import com.healthclaim.policy.exception.PolicyNotFoundException;
import com.healthclaim.policy.repository.PolicyRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class PolicyService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final PolicyRepository policyRepository;
    private final PatientRepository patientRepository;

    public PolicyService(PolicyRepository policyRepository, PatientRepository patientRepository) {
        this.policyRepository = policyRepository;
        this.patientRepository = patientRepository;
    }

    @Transactional
    public PolicyResponse create(CreatePolicyRequest request) {
        policyRepository.findByPolicyNumber(request.policyNumber()).ifPresent(existing -> {
            throw new PolicyAlreadyExistsException(request.policyNumber());
        });

        Policy policy = new Policy();
        policy.setPolicyNumber(request.policyNumber());
        policy.setPolicyType(request.policyType());
        policy.setHolder(resolveHolder(request));
        policy.setEffectiveDate(request.effectiveDate());
        policy.setExpiryDate(request.expiryDate());
        policy.setCoverageLimit(request.coverageLimit());
        policy.setDeductible(request.deductible() != null ? request.deductible() : BigDecimal.ZERO);
        policy.setCopayPercentage(request.copayPercentage() != null ? request.copayPercentage() : BigDecimal.ZERO);
        policy.setCurrency(StringUtils.hasText(request.currency()) ? request.currency() : DEFAULT_CURRENCY);
        policy.setStatus(deriveStatus(request.expiryDate()));
        policy.setWaitingPeriods(request.waitingPeriods());
        policy.setExclusions(request.exclusions());
        policy.setCoverageRules(request.coverageRules());

        return PolicyMapper.toResponse(policyRepository.save(policy));
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(UUID policyId) {
        return PolicyMapper.toResponse(policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId.toString())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PolicyResponse> list(Pageable pageable) {
        return PagedResponse.from(policyRepository.findAll(pageable).map(PolicyMapper::toResponse));
    }

    private Patient resolveHolder(CreatePolicyRequest request) {
        if (!StringUtils.hasText(request.holderReference())) {
            return null;
        }
        return patientRepository.findByReference(request.holderReference()).orElseGet(() -> {
            Patient patient = new Patient();
            patient.setReference(request.holderReference());
            patient.setDisplayName(StringUtils.hasText(request.holderName())
                    ? request.holderName() : request.holderReference());
            return patientRepository.save(patient);
        });
    }

    private static PolicyStatus deriveStatus(LocalDate expiryDate) {
        return (expiryDate != null && expiryDate.isBefore(LocalDate.now()))
                ? PolicyStatus.EXPIRED : PolicyStatus.ACTIVE;
    }
}
