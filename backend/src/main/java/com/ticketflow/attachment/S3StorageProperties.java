package com.ticketflow.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the S3 adapter keeps the files (app.attachments.s3 in application.yml).
 *
 * @param endpoint empty for real AWS S3; the MinIO address otherwise, e.g. http://localhost:9000
 * @param accessKey empty to use the default AWS credentials chain (environment, instance role...)
 * @param pathStyle MinIO serves buckets as a path (host/bucket/key) instead of a subdomain
 * @param createBucket create the bucket at startup when it does not exist (local and tests)
 */
@ConfigurationProperties("app.attachments.s3")
public record S3StorageProperties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        boolean createBucket) {
}
