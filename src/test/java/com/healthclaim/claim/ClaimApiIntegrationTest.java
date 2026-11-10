package com.healthclaim.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.patient.domain.Patient;
import com.healthclaim.patient.repository.PatientRepository;
import com.healthclaim.policy.domain.Policy;
import com.healthclaim.policy.domain.PolicyStatus;
import com.healthclaim.policy.domain.PolicyType;
import com.healthclaim.policy.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "REVIEWER", "USER"})
class ClaimApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private PatientRepository patients;
    @Autowired
    private ClaimRepository claims;

    @Test
    void createThenGetClaim() throws Exception {
        createPolicy("POL-CG");

        String body = mvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claimRequest("POL-CG", "PAT-CG", new BigDecimal("145000.00")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimId", notNullValue()))
                .andExpect(jsonPath("$.claimNumber", notNullValue()))
                .andExpect(jsonPath("$.policyNumber", is("POL-CG")))
                .andExpect(jsonPath("$.status", is("SUBMITTED")))
                .andReturn().getResponse().getContentAsString();

        String claimId = om.readTree(body).get("claimId").asText();

        mvc.perform(get("/api/v1/claims/{id}", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimNumber", notNullValue()))
                .andExpect(jsonPath("$.claimAmount", is(145000.00)));
    }

    @Test
    void listFilteredByPolicy() throws Exception {
        createPolicy("POL-LIST");
        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claimRequest("POL-LIST", "PAT-LIST", new BigDecimal("1000.00")))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/claims").param("policyNumber", "POL-LIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", notNullValue()))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].policyNumber", is("POL-LIST")));
    }

    @Test
    void validationErrorReturns400() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("policyNumber", "");                 // blank
        bad.put("patient", Map.of("reference", "P", "displayName", "N"));
        bad.put("claimAmount", -5);                  // not positive

        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.fieldErrors.policyNumber", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.claimAmount", notNullValue()));
    }

    @Test
    void getMissingClaimReturns404() throws Exception {
        mvc.perform(get("/api/v1/claims/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("CLAIM_NOT_FOUND")));
    }

    @Test
    void unknownPolicyReturns404() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claimRequest("NO-SUCH-POLICY", "PAT-X", new BigDecimal("100.00")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("POLICY_NOT_FOUND")));
    }

    private Map<String, Object> claimRequest(String policyNumber, String patientRef, BigDecimal amount) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("policyNumber", policyNumber);
        req.put("patient", Map.of("reference", patientRef, "displayName", "Synthetic Patient"));
        req.put("provider", "Synthetic Hospital");
        req.put("diagnosis", "Gallstones");
        req.put("procedureName", "Laparoscopic Cholecystectomy");
        req.put("claimAmount", amount);
        req.put("currency", "INR");
        return req;
    }

    private void createPolicy(String policyNumber) {
        Patient holder = new Patient();
        holder.setReference("HOLD-" + policyNumber);
        holder.setDisplayName("Policy Holder");
        patients.save(holder);

        Policy policy = new Policy();
        policy.setPolicyNumber(policyNumber);
        policy.setPolicyType(PolicyType.INDIVIDUAL);
        policy.setHolder(holder);
        policy.setEffectiveDate(LocalDate.now().minusYears(1));
        policy.setExpiryDate(LocalDate.now().plusYears(1));
        policy.setCoverageLimit(new BigDecimal("500000.00"));
        policy.setDeductible(new BigDecimal("5000.00"));
        policy.setCopayPercentage(new BigDecimal("10.00"));
        policy.setCurrency("INR");
        policy.setStatus(PolicyStatus.ACTIVE);
        policies.save(policy);
    }
}
