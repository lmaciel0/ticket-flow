package com.ticketflow.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits login and sign-up attempts per client IP (brute force, credential stuffing, mass sign-up). It sits in
 * the Spring Security chain right after CORS, so a 429 still carries the CORS headers the browser needs, and it
 * answers before the controller, so a blocked attempt never pays for a BCrypt hash. Not a Spring bean on
 * purpose: Spring Boot would also register a bean filter in front of the whole chain, before CORS.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final String BODY =
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"Muitas tentativas. Tente de novo em %s.\"%s}";

    private final boolean enabled;
    private final ClockTimeMeter timeMeter;
    private final List<RouteLimiter> routes;

    public LoginRateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.timeMeter = new ClockTimeMeter(clock);
        this.routes = List.of(
                new RouteLimiter("/api/auth/login", properties.login()),
                new RouteLimiter("/api/auth/register", properties.register()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || route(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteLimiter route = route(request);
        String path = route.path;
        String ip = request.getRemoteAddr();
        ConsumptionProbe probe = route.bucketFor(clientKey(ip)).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        // TEMPORARY (roadmap 3.2 probe): which proxy headers reach the API, directly and through the site's
        // /api rewrite. Remove once the probe is read.
        log.warn("Too many attempts on {} from {} [x-forwarded-for={}, true-client-ip={}, x-real-ip={}, forwarded={}]",
                path, ip, headerForLog(request, "X-Forwarded-For"), headerForLog(request, "True-Client-IP"),
                headerForLog(request, "X-Real-IP"), headerForLog(request, "Forwarded"));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Runs outside Spring MVC, so the trace id is added here instead of by TraceIdProblemAdvice.
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        response.getWriter().write(BODY.formatted(waitText(retryAfter),
                traceId == null ? "" : ",\"traceId\":\"" + traceId + "\""));
    }

    /** A header value safe for one log line: printable ASCII only, at most 200 characters. */
    private static String headerForLog(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            return "-";
        }
        String printable = value.replaceAll("[^ -~]", "?");
        return printable.length() > 200 ? printable.substring(0, 200) + "..." : printable;
    }

    /** "12 s" under a minute, "20 min" (rounded up) from a minute on: nobody counts 1200 seconds. */
    static String waitText(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds + 59) / 60 + " min";
    }

    /**
     * Matched the way Spring Security and Spring MVC match (decoded path), so "/api/auth/logi%6E", which they route
     * to the login, cannot slip past the limit.
     */
    private RouteLimiter route(HttpServletRequest request) {
        for (RouteLimiter route : routes) {
            if (route.matcher.matches(request)) {
                return route;
            }
        }
        return null;
    }

    /**
     * The IP, except that an IPv6 address counts for its whole /64: one subscriber usually holds a /64 and could
     * otherwise pick a new address for every attempt.
     */
    static String clientKey(String ip) {
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            // An address literal (the container always gives one) is parsed, never looked up in DNS.
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (address.length != 16) {
                return ip;
            }
            return HexFormat.of().formatHex(address, 0, 8) + "::/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /** The buckets of one route. An idle IP is forgotten once its bucket would be full again. */
    private final class RouteLimiter {

        private final String path;
        private final RequestMatcher matcher;
        /** Immutable, so every bucket of the route shares it. */
        private final Bandwidth bandwidth;
        private final Cache<String, Bucket> buckets;

        RouteLimiter(String path, RateLimitProperties.Limit limit) {
            this.path = path;
            this.matcher = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path);
            this.bandwidth = Bandwidth.builder().capacity(limit.capacity())
                    .refillGreedy(limit.capacity(), limit.period())
                    .build();
            // 20 thousand IPs is a few MB on the 512 MB free instance. A flood of new IPs beyond that evicts the
            // least recently used ones, which only resets their count.
            this.buckets = Caffeine.newBuilder()
                    .expireAfterAccess(limit.period())
                    .maximumSize(20_000)
                    .build();
        }

        Bucket bucketFor(String ip) {
            return buckets.get(ip, key -> Bucket.builder()
                    .addLimit(bandwidth)
                    .withCustomTimePrecision(timeMeter)
                    .build());
        }
    }
}
