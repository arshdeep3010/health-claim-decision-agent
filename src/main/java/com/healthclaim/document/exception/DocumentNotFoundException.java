package com.healthclaim.document.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised when a referenced document does not exist.
 */
public class DocumentNotFoundException extends ApiException {

    public DocumentNotFoundException(UUID documentId) {
        super("Document not found: " + documentId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String errorCode() {
        return "DOCUMENT_NOT_FOUND";
    }
}
