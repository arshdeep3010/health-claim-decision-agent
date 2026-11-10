package com.healthclaim.policy.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PolicyNotFoundException extends ApiException {

    public PolicyNotFoundException(String policyNumber) {
        super("Policy not found: " + policyNumber);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String errorCode() {
        return "POLICY_NOT_FOUND";
    }
}
