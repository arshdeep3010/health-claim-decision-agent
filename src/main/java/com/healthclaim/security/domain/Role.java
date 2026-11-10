package com.healthclaim.security.domain;

/**
 * Authorization roles. Mapped to Spring authorities as {@code ROLE_<name>}.
 */
public enum Role {
    USER,
    REVIEWER,
    ADMIN
}
