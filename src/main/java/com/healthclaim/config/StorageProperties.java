package com.healthclaim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Document storage configuration. {@code provider} selects the active
 * {@code DocumentStorageService} implementation: {@code local} (dev) or
 * {@code s3} (deployment; works with AWS S3 and S3-compatible stores).
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(
        @DefaultValue("local") String provider,
        @DefaultValue Local local,
        @DefaultValue S3 s3
) {
    public record Local(@DefaultValue("./data/storage") String baseDir) {
    }

    public record S3(
            @DefaultValue("") String endpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("false") boolean pathStyle
    ) {
    }
}
