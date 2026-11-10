package com.healthclaim.rag.embedding;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when an embedding provider call fails or returns unexpected output.
 */
public class EmbeddingException extends ApiException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_GATEWAY;
    }

    @Override
    public String errorCode() {
        return "EMBEDDING_ERROR";
    }
}
