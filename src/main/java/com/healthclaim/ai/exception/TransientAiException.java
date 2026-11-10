package com.healthclaim.ai.exception;

/**
 * Internal marker for a transient provider failure (5xx, timeout, I/O) that is
 * safe to retry. Not exposed outside the provider.
 */
public class TransientAiException extends RuntimeException {

    public TransientAiException(String message) {
        super(message);
    }

    public TransientAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
