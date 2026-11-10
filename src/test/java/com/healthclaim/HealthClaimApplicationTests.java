package com.healthclaim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the full Spring context starts against a real pgvector PostgreSQL,
 * Flyway migrations run, and Hibernate validates the entity-to-schema mapping.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class HealthClaimApplicationTests {

    @Test
    void contextLoads() {
        // A failure to start the context (e.g. a Flyway/validate mismatch) fails this test.
    }
}
