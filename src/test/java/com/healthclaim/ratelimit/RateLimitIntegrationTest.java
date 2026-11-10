package com.healthclaim.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the expensive-endpoint rate limit returns 429 once the per-caller
 * allowance is exhausted (limit set to 3 for this test).
 */
@SpringBootTest(properties = {
        "app.ratelimit.limit-for-period=3",
        "app.ratelimit.refresh-period-seconds=60"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser(username = "rl-user", roles = {"ADMIN", "REVIEWER", "USER"})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void analyzeIsRateLimitedPerCaller() throws Exception {
        String claimId = setupClaim("POL-RL1");

        // Allowance is 3; the first three succeed.
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                    .andExpect(status().isOk());
        }
        // The fourth is rejected with 429.
        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("RATE_LIMITED")));
    }

    private String setupClaim(String policyNumber) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyNumber", policyNumber);
        policy.put("policyType", "INDIVIDUAL");
        policy.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        policy.put("expiryDate", LocalDate.now().plusYears(1).toString());
        policy.put("coverageLimit", 500000);
        policy.put("currency", "INR");
        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(policy))).andExpect(status().isCreated());

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("policyNumber", policyNumber);
        claim.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        claim.put("diagnosis", "Gallstones");
        claim.put("procedureName", "Laparoscopic Cholecystectomy");
        claim.put("claimAmount", 145000);
        claim.put("currency", "INR");
        String resp = mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(claim)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("claimId").asText();
    }
}
