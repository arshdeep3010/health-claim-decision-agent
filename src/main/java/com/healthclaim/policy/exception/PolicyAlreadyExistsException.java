package com.healthclaim.policy.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PolicyAlreadyExistsException extends ApiException {

    public PolicyAlreadyExistsException(String policyNumber) {
        super("Policy already exists: " + policyNumber);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String errorCode() {
        return "POLICY_ALREADY_EXISTS";
    }
}
