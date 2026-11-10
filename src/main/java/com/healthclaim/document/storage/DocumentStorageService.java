package com.healthclaim.document.storage;

/**
 * Abstraction over durable document storage. Files are addressed by an opaque,
 * server-generated {@code storageKey} (never a user-supplied filename). A local
 * filesystem implementation is used for development; an S3-compatible
 * implementation is provided for deployment.
 */
public interface DocumentStorageService {

    /**
     * Stores the given content and returns a newly generated storage key.
     */
    String store(byte[] content, String contentType);

    /**
     * Retrieves previously stored content by its storage key.
     */
    byte[] retrieve(String storageKey);

    /**
     * Deletes stored content; no-op if it does not exist.
     */
    void delete(String storageKey);
}
