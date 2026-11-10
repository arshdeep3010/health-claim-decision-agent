package com.healthclaim.security.dto;

import java.time.Instant;

public record LoginResponse(
        String token,
        String tokenType,
        String role,
        Instant expiresAt
) {
}
