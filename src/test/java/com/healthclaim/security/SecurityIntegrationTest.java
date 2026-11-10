package com.healthclaim.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.security.domain.Role;
import com.healthclaim.security.repository.AppUserRepository;
import com.healthclaim.security.service.UserService;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real JWT authentication and role-based authorization rules
 * (no {@code @WithMockUser}): login, 401 for anonymous/invalid tokens, and 403
 * when a role is insufficient.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private UserService userService;
    @Autowired
    private AppUserRepository userRepository;

    private static final String ADMIN = "sec-admin";
    private static final String USER = "sec-user";
    private static final String PASSWORD = "s3cret-password";

    @BeforeEach
    void seedUsers() {
        userService.createIfAbsent(ADMIN, PASSWORD, Role.ADMIN);
        userService.createIfAbsent(USER, PASSWORD, Role.USER);
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(get("/api/v1/claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void loginWithBadCredentialsReturns401() throws Exception {
        Map<String, Object> body = Map.of("username", ADMIN, "password", "wrong");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/claims").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanSubmitAndAnalyzeOwnClaimButNotManagePolicies() throws Exception {
        String adminToken = login(ADMIN);
        String userToken = login(USER);

        // ADMIN creates a policy; USER cannot.
        String policyBody = om.writeValueAsString(policy("POL-SEC1"));
        mvc.perform(post("/api/v1/policies").header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content(policyBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("FORBIDDEN")));
        mvc.perform(post("/api/v1/policies").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(policyBody))
                .andExpect(status().isCreated());

        // USER can create and analyze a claim.
        String resp = mvc.perform(post("/api/v1/claims").header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(claim("POL-SEC1"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String claimId = om.readTree(resp).get("claimId").asText();
        mvc.perform(post("/api/v1/claims/{id}/analyze", claimId).header("Authorization", bearer(userToken)))
                .andExpect(status().isOk());

        // USER cannot review or view the audit trail (ADMIN/REVIEWER only).
        mvc.perform(post("/api/v1/claims/{id}/review", claimId).header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("reviewDecision", "APPROVE"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/claims/{id}/audit", claimId).header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesUsersAndPasswordsAreHashed() throws Exception {
        String adminToken = login(ADMIN);
        Map<String, Object> newUser = Map.of("username", "sec-new", "password", "another-secret", "role", "REVIEWER");

        mvc.perform(post("/api/v1/admin/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(newUser)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role", is("REVIEWER")))
                .andExpect(jsonPath("$.id", notNullValue()));

        // Stored as a BCrypt hash, never plaintext.
        String hash = userRepository.findByUsername("sec-new").orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").isNotEqualTo("another-secret");

        // Duplicate username -> 409.
        mvc.perform(post("/api/v1/admin/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(newUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("USERNAME_ALREADY_EXISTS")));
    }

    // --- helpers ---

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private Map<String, Object> policy(String policyNumber) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyNumber", policyNumber);
        p.put("policyType", "INDIVIDUAL");
        p.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        p.put("expiryDate", LocalDate.now().plusYears(1).toString());
        p.put("coverageLimit", 500000);
        p.put("currency", "INR");
        return p;
    }

    private Map<String, Object> claim(String policyNumber) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("policyNumber", policyNumber);
        c.put("patient", Map.of("reference", "PAT-" + policyNumber, "displayName", "Synthetic Patient"));
        c.put("diagnosis", "Gallstones");
        c.put("procedureName", "Laparoscopic Cholecystectomy");
        c.put("claimAmount", 145000);
        c.put("currency", "INR");
        return c;
    }
}
