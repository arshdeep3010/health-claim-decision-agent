package com.healthclaim.document.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when the storage backend cannot read/write a document.
 */
public class DocumentStorageException extends ApiException {

    public DocumentStorageException(String message) {
        super(message);
    }

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Override
    public String errorCode() {
        return "STORAGE_ERROR";
    }
}
