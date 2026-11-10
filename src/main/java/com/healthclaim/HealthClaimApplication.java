package com.healthclaim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Health Claim Decision Agent.
 *
 * <p>This is a modular monolith: all modules (claim, policy, document, rag, ai,
 * decision, review, audit, ...) live inside this single deployable application.
 *
 * <p><strong>SYNTHETIC DATA ONLY.</strong> This system does not provide real
 * insurance authorization, medical advice, or production insurance decisions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HealthClaimApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthClaimApplication.class, args);
    }
}
