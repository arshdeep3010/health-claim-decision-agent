package com.healthclaim.decision.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised for unexpected failures while processing a decision. (Provider/RAG
 * failures are handled by routing to HUMAN_REVIEW, not by throwing this.)
 */
public class DecisionProcessingException extends ApiException {

    public DecisionProcessingException(String message) {
        super(message);
    }

    public DecisionProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Override
    public String errorCode() {
        return "DECISION_PROCESSING_ERROR";
    }
}
