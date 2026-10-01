package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The limits and the proxy setting come from application.yml, so they are checked as the real app loads them. */
class RateLimitConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void limitsLoginAndSignUpByDefault() {
        runner.run(context -> {
            RateLimitProperties properties = Binder.get(context.getEnvironment())
                    .bind("app.rate-limit", RateLimitProperties.class).get();
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.login()).isEqualTo(new RateLimitProperties.Limit(5, Duration.ofMinutes(1)));
            assertThat(properties.register()).isEqualTo(new RateLimitProperties.Limit(3, Duration.ofHours(1)));
        });
    }

    @Test
    void trustsForwardedHeadersFromTheProxy() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy"))
                .isEqualTo("native"));
    }

    @Test
    void refusesALimitThatWouldBlockEverything() {
        assertThatThrownBy(() -> new RateLimitProperties.Limit(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(5, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(5, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
