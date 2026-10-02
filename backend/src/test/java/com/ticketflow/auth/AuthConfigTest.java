package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Session lifetimes come from application.yml, so they are checked as the real app loads them. */
class AuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void accessTokensAreShortAndSessionsLastAWeek() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            assertThat(binder.bind("app.jwt.ttl", Duration.class).get()).isEqualTo(Duration.ofMinutes(15));
            AuthProperties auth = binder.bind("app.auth", AuthProperties.class).get();
            assertThat(auth.refreshTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(auth.handoffTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(auth.reuseGrace()).isEqualTo(Duration.ofSeconds(30));
            assertThat(auth.refreshCookie().secure()).isTrue();
        });
    }
}
