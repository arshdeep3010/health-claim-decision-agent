package com.healthclaim.document.storage;

import com.healthclaim.config.StorageProperties;
import com.healthclaim.document.exception.DocumentStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

    @TempDir
    Path tempDir;

    private LocalDocumentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalDocumentStorage(
                new StorageProperties("local", new StorageProperties.Local(tempDir.toString()),
                        new StorageProperties.S3("", "us-east-1", "", "", "", false)));
        storage.init();
    }

    @Test
    void storesRetrievesAndDeletes() {
        byte[] data = "%PDF-1.7 synthetic".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(data, "application/pdf");
        assertThat(key).isNotBlank();
        assertThat(storage.retrieve(key)).isEqualTo(data);

        storage.delete(key);
        assertThatThrownBy(() -> storage.retrieve(key)).isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void rejectsPathTraversalKeys() {
        assertThatThrownBy(() -> storage.retrieve("../escape"))
                .isInstanceOf(DocumentStorageException.class);
    }
}
