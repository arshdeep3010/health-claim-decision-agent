package com.healthclaim.security.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String role,
        boolean enabled,
        Instant createdAt
) {
}
