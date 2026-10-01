package com.ticketflow.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * API tests in S3 mode: Adobe's S3Mock (an S3-compatible server made for tests) in Docker, shared by
 * every subclass (one container, one context). The properties are set before the context starts, so
 * {@code @ConditionalOnProperty} already sees "s3". S3Mock accepts any credentials and path-style URLs.
 */
public abstract class S3IntegrationTest extends IntegrationTest {

    private static final int S3_PORT = 9090;

    protected static final GenericContainer<?> S3_MOCK =
            new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest")).withExposedPorts(S3_PORT);

    static {
        S3_MOCK.start();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.storage", () -> "s3");
        registry.add("app.attachments.s3.endpoint",
                () -> "http://%s:%d".formatted(S3_MOCK.getHost(), S3_MOCK.getMappedPort(S3_PORT)));
        registry.add("app.attachments.s3.access-key", () -> "test");
        registry.add("app.attachments.s3.secret-key", () -> "test");
        registry.add("app.attachments.s3.path-style", () -> "true");
        registry.add("app.attachments.s3.create-bucket", () -> "true");
    }
}
