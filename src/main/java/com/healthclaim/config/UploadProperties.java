package com.healthclaim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for uploaded documents (application-level check in addition to the
 * servlet multipart limits).
 */
@ConfigurationProperties("app.upload")
public record UploadProperties(
        @DefaultValue("15728640") long maxSizeBytes
) {
}
