package com.healthclaim.security.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class UsernameAlreadyExistsException extends ApiException {

    public UsernameAlreadyExistsException(String username) {
        super("Username already exists: " + username);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String errorCode() {
        return "USERNAME_ALREADY_EXISTS";
    }
}
