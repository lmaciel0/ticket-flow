package com.ticketflow.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Attempts allowed per client IP on the public auth routes, read from app.rate-limit in application.yml. */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(boolean enabled, Limit login, Limit register) {

    public RateLimitProperties {
        if (login == null || register == null) {
            throw new IllegalStateException("Missing app.rate-limit.login or app.rate-limit.register");
        }
    }

    /** {@code capacity} attempts per {@code period}, given back gradually (one every period / capacity). */
    public record Limit(int capacity, Duration period) {

        public Limit {
            if (capacity < 1) {
                throw new IllegalStateException("Rate limit capacity must be at least 1");
            }
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalStateException("Rate limit period must be positive");
            }
        }
    }
}
