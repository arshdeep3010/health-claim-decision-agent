package com.healthclaim.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for domain exceptions that map cleanly onto an HTTP response.
 * Concrete module exceptions (ClaimNotFoundException, PolicyNotFoundException, ...)
 * extend this so the global handler can render them without per-type wiring.
 */
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    protected ApiException(String message, Throwable cause) {
        super(message, cause);
    }

    /** HTTP status this exception should produce. */
    public abstract HttpStatus status();

    /** Stable, machine-readable error code (e.g. CLAIM_NOT_FOUND). */
    public abstract String errorCode();
}
