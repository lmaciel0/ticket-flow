package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Login: 5 per minute per IP. Sign-up: 3 per hour per IP. Every attempt counts, whatever the answer. */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class LoginRateLimitApiTest extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    private User ana;
    /** A fresh IP per test: the limiter is a singleton and keeps its buckets between tests. */
    private String ip;
    private static int nextIp = 1;

    @BeforeEach
    void setUp() {
        ana = createUser("Ana", Role.REQUESTER);
        ip = "203.0.113." + nextIp++;
    }

    private ResultActions login(String fromIp, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(fromIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(ana.getEmail(), password)));
    }

    private ResultActions register(String fromIp, String email) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .with(request -> {
                    request.setRemoteAddr(fromIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Visitante", "email": "%s", "password": "password123"}
                        """.formatted(email)));
    }

    @Test
    void theSixthLoginFromTheSameIpIsRejected() throws Exception {
        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, "wrong-password").andExpect(status().isUnauthorized());
        login(ip, "").andExpect(status().isBadRequest());
        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, "wrong-password").andExpect(status().isUnauthorized());

        login(ip, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Muitas tentativas. Tente de novo em 12 s."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void oneMoreAttemptComesBackEveryTwelveSeconds() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(12));

        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"));
    }

    @Test
    void anotherIpHasItsOwnLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        login(ip + "0", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void signUpHasItsOwnHourlyLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        register(ip, "um@test.com").andExpect(status().isCreated());
        register(ip, "dois@test.com").andExpect(status().isCreated());
        register(ip, "tres@test.com").andExpect(status().isCreated());
        register(ip, "quatro@test.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1200"));
    }

    @Test
    void anEncodedPathCountsAsTheSameRoute() throws Exception {
        // Spring decodes "%6E" to "n" when routing, so the filter must not let this reach the login unlimited.
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        mvc.perform(post(URI.create("/api/auth/logi%6E"))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(ana.getEmail(), PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void ipv6AddressesOfTheSameNetworkShareTheLimit() throws Exception {
        // One IPv6 subscriber holds a whole /64; rotating inside it must not reset the limit.
        String network = "2001:db8:0:" + nextIp + ":";
        for (int i = 0; i < 5; i++) {
            login(network + ":" + (i + 1), PASSWORD).andExpect(status().isOk());
        }
        login(network + ":99", PASSWORD).andExpect(status().isTooManyRequests());

        login("2001:db8:1:" + nextIp + "::1", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void authenticatedRoutesAreNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/tickets")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header("Authorization", bearer(ana)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void preflightDoesNotSpendAttempts() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(options("/api/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header("Origin", FRONTEND)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theRejectionCarriesTheCorsHeaders() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        String exposed = mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .header("Origin", FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"x@test.com\", \"password\": \"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andReturn().getResponse().getHeader("Access-Control-Expose-Headers");
        assertThat(exposed).contains("Retry-After");
    }
}
