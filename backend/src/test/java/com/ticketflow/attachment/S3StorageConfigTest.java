package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.support.S3IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

/** A wrong S3 setup must stop the application at startup, not surface later as a 500 on the first upload. */
class S3StorageConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfig.class)
            .withPropertyValues(
                    "app.attachments.storage=s3",
                    "app.attachments.s3.endpoint=" + S3IntegrationTest.endpoint(),
                    "app.attachments.s3.region=us-east-1",
                    "app.attachments.s3.bucket=ticket-flow-attachments",
                    "app.attachments.s3.access-key=test",
                    "app.attachments.s3.secret-key=test",
                    "app.attachments.s3.path-style=true",
                    "app.attachments.s3.create-bucket=true");

    @Test
    void aValidSetupStarts() {
        runner.run(context -> assertThat(context).hasSingleBean(S3Client.class));
    }

    @Test
    void aBlankBucketStopsTheStartup() {
        runner.withPropertyValues("app.attachments.s3.bucket=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("bucket"));
    }

    @Test
    void aMissingBucketThatMustNotBeCreatedStopsTheStartup() {
        runner.withPropertyValues("app.attachments.s3.bucket=bucket-que-nao-existe",
                        "app.attachments.s3.create-bucket=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(
                                "Attachments bucket 'bucket-que-nao-existe' does not exist"));
    }
}
