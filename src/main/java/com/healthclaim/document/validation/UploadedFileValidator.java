package com.healthclaim.document.validation;

import com.healthclaim.config.UploadProperties;
import com.healthclaim.document.exception.InvalidUploadException;
import com.healthclaim.document.exception.UnsupportedDocumentTypeException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Validates uploaded documents: non-empty, within size limits, and actually a PDF
 * (verified by magic bytes, not just the filename extension). Computes a SHA-256
 * for de-duplication. Guards against path traversal in the original filename.
 */
@Component
public class UploadedFileValidator {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final UploadProperties properties;

    public UploadedFileValidator(UploadProperties properties) {
        this.properties = properties;
    }

    public ValidatedUpload validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("Uploaded file is empty");
        }
        if (file.getSize() > properties.maxSizeBytes()) {
            throw new InvalidUploadException(
                    "File exceeds the maximum allowed size of " + properties.maxSizeBytes() + " bytes");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidUploadException("Could not read the uploaded file");
        }
        if (!hasPdfMagic(content)) {
            throw new UnsupportedDocumentTypeException("Only PDF files are supported");
        }
        String original = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename());
        if (original.contains("..")) {
            throw new InvalidUploadException("Invalid filename");
        }
        return new ValidatedUpload(content, "application/pdf", original, sha256Hex(content), content.length);
    }

    private static boolean hasPdfMagic(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
