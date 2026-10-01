package com.ticketflow.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.category.CategoryRepository;
import com.ticketflow.comment.CommentRepository;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** The seeder bean only exists in the "demo" profile, so the test builds it by hand. */
class DemoDataSeederTest extends IntegrationTest {

    @Autowired CategoryRepository categories;
    @Autowired TicketRepository tickets;
    @Autowired CommentRepository comments;
    @Autowired HistoryRecorder history;
    @Autowired SlaCalculator sla;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate transaction;

    DemoDataSeeder seeder;

    @BeforeEach
    void createSeeder() {
        seeder = new DemoDataSeeder(jdbc, userRepository, categories, tickets, comments, history, sla,
                passwordEncoder, clock, transaction);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void seedsDemoUsersAndTicketsInEverySlaSituation() throws Exception {
        assertThat(seeder.resetIfDue()).isTrue();

        assertThat(count("users")).isEqualTo(5);
        assertThat(count("tickets")).isEqualTo(DemoDataSeeder.TICKET_COUNT);
        assertThat(userRepository.findAll()).allMatch(User::isDemo);

        String token = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "gestor@ticketflow.demo", "password": "demo1234"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + com.jayway.jsonpath.JsonPath.read(token, "$.token");
        for (String situation : new String[] {"OVERDUE", "AT_RISK"}) {
            String body = mvc.perform(get("/api/tickets").param("sla", situation).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(readLong(body, "$.totalElements")).as(situation).isPositive();
        }
        mvc.perform(get("/api/tickets").param("status", "WAITING_REQUESTER").header("Authorization", bearer))
                .andExpect(jsonPath("$.content[0].sla").value("PAUSED"));

        String all = mvc.perform(get("/api/tickets").param("size", "100").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> firstResponses = com.jayway.jsonpath.JsonPath.read(all, "$.content[*].firstResponse");
        assertThat(firstResponses).contains("PENDING", "OVERDUE", "MET", "BREACHED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE assignee_id IS NOT NULL AND first_responded_at IS NULL",
                Long.class)).isZero();
    }

    @Test
    void doesNotResetAgainWithin24Hours() {
        seeder.resetIfDue();
        User visitor = createUser("Visitante", Role.REQUESTER);

        clock.advance(Duration.ofHours(23));
        assertThat(seeder.resetIfDue()).isFalse();
        assertThat(userRepository.findById(visitor.getId())).isPresent();

        clock.advance(Duration.ofHours(2));
        assertThat(seeder.resetIfDue()).isTrue();
        assertThat(userRepository.findById(visitor.getId())).isEmpty();
    }

    @Test
    void resetNeverReusesIdsSoOldTokensStopWorking() throws Exception {
        seeder.resetIfDue();
        User visitor = createUser("Visitante", Role.REQUESTER);
        String oldToken = bearer(visitor);

        clock.advance(Duration.ofHours(25));
        seeder.resetIfDue();
        createUser("Outra Pessoa", Role.REQUESTER);

        mvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
    }
}
