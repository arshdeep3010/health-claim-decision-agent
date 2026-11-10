package com.healthclaim.document.validation;

/**
 * A validated, in-memory uploaded file ready to be stored.
 */
public record ValidatedUpload(
        byte[] content,
        String contentType,
        String originalFilename,
        String sha256,
        long size
) {
}
