package com.ticketflow.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie. HttpOnly keeps it away from page scripts; SameSite=Strict keeps other sites from
 * using it; Path=/api/auth sends it only to the routes that read it.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "tf_refresh";

    private final AuthProperties properties;
    private final Clock clock;

    public RefreshCookie(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** The Set-Cookie value for a session that ends at {@code refresh.expiresAt()}. */
    public String issue(RefreshTokenService.IssuedRefresh refresh) {
        return base(refresh.token()).maxAge(Duration.between(clock.instant(), refresh.expiresAt())).build().toString();
    }

    /** The Set-Cookie value that deletes the cookie. */
    public String clear() {
        return base("").maxAge(0).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookie().secure())
                .sameSite("Strict")
                .path("/api/auth");
    }
}
