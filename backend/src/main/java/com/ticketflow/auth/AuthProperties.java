package com.ticketflow.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Session lifetimes and the refresh cookie, read from app.auth in application.yml. */
@ConfigurationProperties("app.auth")
public record AuthProperties(Duration refreshTtl, Duration handoffTtl, Duration reuseGrace, RefreshCookie refreshCookie) {

    /** {@code secure: false} only for plain http outside localhost-aware browsers (Safari on http://localhost). */
    public record RefreshCookie(boolean secure) {
    }
}
