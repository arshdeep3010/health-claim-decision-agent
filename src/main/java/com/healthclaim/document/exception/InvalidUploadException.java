package com.healthclaim.document.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when an uploaded file is empty, too large, or otherwise invalid.
 */
public class InvalidUploadException extends ApiException {

    public InvalidUploadException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public String errorCode() {
        return "INVALID_UPLOAD";
    }
}
