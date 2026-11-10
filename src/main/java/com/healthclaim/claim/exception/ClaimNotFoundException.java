package com.healthclaim.claim.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ClaimNotFoundException extends ApiException {

    public ClaimNotFoundException(UUID claimId) {
        super("Claim not found: " + claimId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String errorCode() {
        return "CLAIM_NOT_FOUND";
    }
}
