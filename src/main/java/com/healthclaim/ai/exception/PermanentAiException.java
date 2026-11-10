package com.healthclaim.ai.exception;

/**
 * Internal marker for a permanent provider failure (4xx, malformed response)
 * that must NOT be retried. Not exposed outside the provider.
 */
public class PermanentAiException extends RuntimeException {

    public PermanentAiException(String message) {
        super(message);
    }

    public PermanentAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
