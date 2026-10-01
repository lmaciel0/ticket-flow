package com.ticketflow.attachment;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** Builds the S3 client only when app.attachments.storage=s3; the default (database) needs none of this. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .forcePathStyle(properties.pathStyle());
        if (StringUtils.hasText(properties.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        S3Client client = builder.build();
        ensureBucket(client, properties);
        return client;
    }

    /**
     * Reaches the bucket once at startup, so a wrong endpoint, wrong credentials or a missing bucket stop
     * the application here instead of turning the first upload into a 500.
     */
    private static void ensureBucket(S3Client client, S3StorageProperties properties) {
        String bucket = properties.bucket();
        try {
            client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException missing) {
            if (!properties.createBucket()) {
                throw new IllegalStateException(
                        "Attachments bucket '%s' does not exist: create it or set S3_CREATE_BUCKET=true"
                                .formatted(bucket), missing);
            }
            client.createBucket(b -> b.bucket(bucket));
            log.info("Created attachments bucket {}", bucket);
        }
    }
}
