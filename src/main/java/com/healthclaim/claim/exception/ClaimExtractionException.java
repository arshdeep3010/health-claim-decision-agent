package com.healthclaim.claim.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when the LLM's extraction output is missing or structurally invalid
 * (and therefore cannot be trusted). Distinct from a provider/transport failure.
 */
public class ClaimExtractionException extends ApiException {

    public ClaimExtractionException(String message) {
        super(message);
    }

    public ClaimExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String errorCode() {
        return "CLAIM_EXTRACTION_ERROR";
    }
}
