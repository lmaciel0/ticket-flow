package com.ticketflow.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
                    + "\"detail\":\"Muitas tentativas. Tente de novo em %d s.\"%s}";

    private final boolean enabled;
    private final ClockTimeMeter timeMeter;
    /** Path → (IP → bucket). */
    private final Map<String, RouteLimiter> routes;

    public LoginRateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.timeMeter = new ClockTimeMeter(clock);
        this.routes = Map.of(
                "/api/auth/login", new RouteLimiter(properties.login()),
                "/api/auth/register", new RouteLimiter(properties.register()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !HttpMethod.POST.matches(request.getMethod()) || !routes.containsKey(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        String ip = request.getRemoteAddr();
        ConsumptionProbe probe = routes.get(path).bucketFor(ip).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        log.warn("Too many attempts on {} from {}", path, ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Runs outside Spring MVC, so the trace id is added here instead of by TraceIdProblemAdvice.
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        response.getWriter().write(BODY.formatted(retryAfter,
                traceId == null ? "" : ",\"traceId\":\"" + traceId + "\""));
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** The buckets of one route. An idle IP is forgotten once its bucket would be full again. */
    private final class RouteLimiter {

        private final RateLimitProperties.Limit limit;
        private final Cache<String, Bucket> buckets;

        RouteLimiter(RateLimitProperties.Limit limit) {
            this.limit = limit;
            this.buckets = Caffeine.newBuilder()
                    .expireAfterAccess(limit.period())
                    .maximumSize(100_000)
                    .build();
        }

        Bucket bucketFor(String ip) {
            return buckets.get(ip, key -> Bucket.builder()
                    .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                            .refillGreedy(limit.capacity(), limit.period()))
                    .withCustomTimePrecision(timeMeter)
                    .build());
        }
    }
}
