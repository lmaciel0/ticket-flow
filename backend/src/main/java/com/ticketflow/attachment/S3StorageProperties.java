package com.ticketflow.attachment;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the S3 adapter keeps the files (app.attachments.s3 in application.yml). Validated at startup:
 * a blank region or bucket stops the application instead of failing on the first upload.
 *
 * @param endpoint empty for real AWS S3; the S3-compatible server's address otherwise, e.g. http://localhost:9090
 * @param accessKey empty to use the default AWS credentials chain (environment, instance role...)
 * @param pathStyle servers like MinIO and S3Mock serve buckets as a path (host/bucket/key), not a subdomain
 * @param createBucket create the bucket at startup when it does not exist (local and tests only)
 */
@Validated
@ConfigurationProperties("app.attachments.s3")
public record S3StorageProperties(
        String endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        boolean createBucket) {
}
