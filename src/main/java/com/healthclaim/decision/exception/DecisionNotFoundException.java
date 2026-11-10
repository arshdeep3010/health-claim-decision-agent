package com.healthclaim.decision.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised when a decision is requested for a claim that has not been analyzed yet.
 */
public class DecisionNotFoundException extends ApiException {

    public DecisionNotFoundException(UUID claimId) {
        super("No decision found for claim " + claimId + "; analyze it first.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String errorCode() {
        return "DECISION_NOT_FOUND";
    }
}
