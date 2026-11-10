package com.healthclaim.ai.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when an AI provider is unavailable, times out, trips the circuit
 * breaker, or returns an unusable response. The decision layer maps this to
 * HUMAN_REVIEW; it never results in an automatic approve/reject.
 */
public class AiProviderException extends ApiException {

    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_GATEWAY;
    }

    @Override
    public String errorCode() {
        return "AI_PROVIDER_ERROR";
    }
}
