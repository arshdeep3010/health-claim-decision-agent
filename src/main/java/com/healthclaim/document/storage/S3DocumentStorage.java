package com.healthclaim.document.storage;

import com.healthclaim.config.StorageProperties;
import com.healthclaim.document.exception.DocumentStorageException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.UUID;

/**
 * S3-compatible object storage for deployment (AWS S3, MinIO, Cloudflare R2, ...).
 * Objects are addressed by generated keys, never user filenames. Credentials come
 * from static config when provided, otherwise the default AWS credentials chain
 * (IAM role / env), which is the recommended production setup.
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3DocumentStorage(StorageProperties properties) {
        StorageProperties.S3 cfg = properties.s3();
        if (!StringUtils.hasText(cfg.bucket())) {
            throw new IllegalStateException("S3_BUCKET is required when STORAGE_PROVIDER=s3");
        }
        this.bucket = cfg.bucket();

        S3ClientBuilderHolder holder = new S3ClientBuilderHolder(cfg);
        this.s3 = holder.build();
        log.info("S3 document storage configured (bucket='{}', region='{}', endpoint='{}', pathStyle={})",
                cfg.bucket(), cfg.region(), StringUtils.hasText(cfg.endpoint()) ? cfg.endpoint() : "default",
                cfg.pathStyle());
    }

    @Override
    public String store(byte[] content, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new DocumentStorageException("Failed to store document in S3", e);
        }
        return key;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new DocumentStorageException("Document not found in S3: " + storageKey, e);
        } catch (S3Exception e) {
            throw new DocumentStorageException("Failed to read document from S3 " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (S3Exception e) {
            throw new DocumentStorageException("Failed to delete document from S3 " + storageKey, e);
        }
    }

    @PreDestroy
    void close() {
        s3.close();
    }

    /** Builds the S3 client from configuration (static creds + endpoint override are optional). */
    private static final class S3ClientBuilderHolder {
        private final StorageProperties.S3 cfg;

        S3ClientBuilderHolder(StorageProperties.S3 cfg) {
            this.cfg = cfg;
        }

        S3Client build() {
            var builder = S3Client.builder().region(Region.of(cfg.region()));
            if (StringUtils.hasText(cfg.accessKey()) && StringUtils.hasText(cfg.secretKey())) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            }
            if (StringUtils.hasText(cfg.endpoint())) {
                builder.endpointOverride(URI.create(cfg.endpoint()));
            }
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(cfg.pathStyle())
                    .build());
            return builder.build();
        }
    }
}
