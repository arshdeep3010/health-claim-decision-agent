package com.healthclaim.document.storage;

import com.healthclaim.TestcontainersConfiguration;
import com.healthclaim.document.exception.DocumentStorageException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the S3 storage path against a real S3-compatible server (LocalStack),
 * proving store/retrieve/delete work through the AWS SDK with path-style access.
 */
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class S3DocumentStorageIntegrationTest {

    private static final String BUCKET = "hcda-test";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.provider", () -> "s3");
        registry.add("app.storage.s3.endpoint",
                () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("app.storage.s3.region", LOCALSTACK::getRegion);
        registry.add("app.storage.s3.bucket", () -> BUCKET);
        registry.add("app.storage.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("app.storage.s3.secret-key", LOCALSTACK::getSecretKey);
        registry.add("app.storage.s3.path-style", () -> "true");
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
        }
    }

    @Autowired
    private DocumentStorageService storage;

    @Test
    void usesS3ImplementationAndRoundTrips() {
        assertThat(storage).isInstanceOf(S3DocumentStorage.class);

        byte[] data = "%PDF-1.7 synthetic policy".getBytes(StandardCharsets.UTF_8);
        String key = storage.store(data, "application/pdf");
        assertThat(key).isNotBlank();
        assertThat(storage.retrieve(key)).isEqualTo(data);

        storage.delete(key);
        assertThatThrownBy(() -> storage.retrieve(key)).isInstanceOf(DocumentStorageException.class);
    }
}
