package com.healthclaim.document.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a document cannot be parsed/processed (e.g. corrupt PDF).
 */
public class DocumentProcessingException extends ApiException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String errorCode() {
        return "DOCUMENT_PROCESSING_ERROR";
    }
}
