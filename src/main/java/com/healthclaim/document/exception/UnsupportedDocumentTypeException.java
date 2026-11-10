package com.healthclaim.document.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when an uploaded file is not a supported type (only PDF is accepted).
 */
public class UnsupportedDocumentTypeException extends ApiException {

    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    }

    @Override
    public String errorCode() {
        return "UNSUPPORTED_MEDIA_TYPE";
    }
}
