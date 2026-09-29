package com.ticketflow.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The browser only lets the frontend call the API if the API allows the frontend's origin.
 * The value comes from CORS_ALLOWED_ORIGINS, typed by hand in the Render dashboard, so it must
 * survive the usual copy-and-paste noise: a trailing slash and spaces after the comma.
 */
@TestPropertySource(properties = "app.cors.allowed-origins=https://ticket-flow-web.onrender.com/ , http://localhost:5173")
class CorsApiTest extends IntegrationTest {

    private static final String FRONTEND = "https://ticket-flow-web.onrender.com";

    @Test
    void allowsTheConfiguredFrontendEvenWithATrailingSlashInTheSetting() throws Exception {
        mvc.perform(options("/api/tickets")
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }

    @Test
    void allowsEveryOriginInTheListEvenWithSpacesAfterTheComma() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void refusesOtherOrigins() throws Exception {
        mvc.perform(options("/api/tickets")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
