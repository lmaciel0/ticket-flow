package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The public demo runs with the "demo" profile. There, the JWT secret must come from the
 * JWT_SECRET environment variable: silently falling back to the secret committed in
 * application.yml would let anyone forge tokens.
 */
class JwtSecretConfigTest {

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesOnly {
    }

    /** Loads application.yml (+ application-<profile>.yml) exactly like the real application does. */
    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class);
    }

    @Test
    void localDevelopmentStartsWithTheDevSecret() {
        runner().run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtProperties.class));
    }

    @Test
    void demoProfileRefusesToStartWithoutJwtSecret() {
        runner("demo")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("JWT_SECRET"));
    }

    @Test
    void demoProfileUsesJwtSecretWhenProvided() {
        runner("demo").withPropertyValues("JWT_SECRET=production-secret-with-at-least-32-bytes!")
                .run(context -> assertThat(context.getBean(JwtProperties.class).secret())
                        .isEqualTo("production-secret-with-at-least-32-bytes!"));
    }
}
