package com.ticketflow.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.ticketflow.auth.TokenService;
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

    protected MockMvc mvc;

    @BeforeEach
    void setUpIntegrationTest() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
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
}
