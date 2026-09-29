package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Render tells each service which port to listen on through the PORT environment variable
 * (10000 by default). Locally, and in docker compose, the API keeps port 8080.
 */
class ServerPortConfigTest {

    /** Loads application.yml exactly like the real application does. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void listensOnPortFromTheEnvironment() {
        runner.withPropertyValues("PORT=10000")
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("10000"));
    }

    @Test
    void keepsPort8080WhenPortIsNotSet() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080"));
    }
}
