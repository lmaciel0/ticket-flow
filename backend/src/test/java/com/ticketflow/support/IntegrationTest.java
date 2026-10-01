package com.ticketflow.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.auth.TokenService;
import com.ticketflow.common.TraceIdFilter;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for API tests: real Spring context + real PostgreSQL (Testcontainers) + MockMvc.
 * Every test starts with an empty database (categories and Flyway's history are kept).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, IntegrationTest.ClockConfiguration.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "password123";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TraceIdFilter traceIdFilter;

    protected MockMvc mvc;

    @BeforeEach
    void setUpIntegrationTest() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(traceIdFilter) // before Spring Security, like in production
                .apply(springSecurity())
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
        clock.reset();
        cleanDatabase();
    }

    private void cleanDatabase() {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'categories')
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }

    protected User createUser(String name, Role role) {
        String email = name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@test.com";
        return userRepository.save(new User(name, email, passwordEncoder.encode(PASSWORD), role, clock.instant()));
    }

    protected String bearer(User user) {
        return "Bearer " + tokenService.issue(user);
    }

    protected long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    /** Creates a ticket through the API, as the given user, and returns its id. */
    protected long createTicket(User requester, String title, String priority) throws Exception {
        String body = mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "description": "Detalhes do problema", "priority": "%s",
                                 "categoryId": %d}
                                """.formatted(title, priority, categoryId("Hardware"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Reads a numeric field (id, version...) from a JSON response body. */
    protected static long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    /** The If-Match value for a ticket version, as the API's ETag header writes it: "3". */
    protected static String etag(long version) {
        return "\"" + version + "\"";
    }
}
