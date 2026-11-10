package com.healthclaim.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger metadata, plus the Bearer JWT scheme so Swagger UI shows an
 * "Authorize" button (get a token from POST /api/v1/auth/login).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI healthClaimOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Health Claim Decision Agent API")
                        .version("v1")
                        .description("""
                                AI-assisted, evidence-grounded health-insurance claim decision support.

                                SYNTHETIC DATA ONLY. This system does NOT provide real insurance
                                authorization, medical advice, or production insurance decisions. All
                                policies, claims, patients and documents are fabricated for demonstration.

                                To call protected endpoints: POST /api/v1/auth/login, copy the token,
                                then click "Authorize" and paste it.""")
                        .license(new License().name("For demonstration / portfolio use"))
                        .contact(new Contact().name("Health Claim Decision Agent")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
