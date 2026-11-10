package com.healthclaim.document.storage;

import com.healthclaim.config.StorageProperties;
import com.healthclaim.document.exception.DocumentStorageException;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Filesystem-backed storage for local development. Content is written under a
 * configured base directory using generated keys; keys are validated so they can
 * never escape the base directory (path-traversal guard).
 *
 * <p>NOT for production persistence — container filesystems are ephemeral. Use
 * the S3-compatible implementation when deployed.
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorageService {

    private final Path baseDir;

    public LocalDocumentStorage(StorageProperties properties) {
        this.baseDir = Paths.get(properties.local().baseDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new DocumentStorageException("Cannot create storage directory: " + baseDir, e);
        }
    }

    @Override
    public String store(byte[] content, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            Files.write(resolve(key), content);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store document", e);
        }
        return key;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read document " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to delete document " + storageKey, e);
        }
    }

    private Path resolve(String storageKey) {
        Path resolved = baseDir.resolve(storageKey).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new DocumentStorageException("Invalid storage key");
        }
        return resolved;
    }
}
