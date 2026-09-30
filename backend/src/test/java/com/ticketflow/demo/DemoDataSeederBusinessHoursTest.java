package com.ticketflow.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.comment.CommentRepository;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.TicketRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** With business hours on, resetting the demo at night must still produce overdue and at-risk tickets. */
@TestPropertySource(properties = "app.sla.business-hours.enabled=true")
class DemoDataSeederBusinessHoursTest extends IntegrationTest {

    @Autowired CategoryRepository categories;
    @Autowired TicketRepository tickets;
    @Autowired CommentRepository comments;
    @Autowired HistoryRecorder history;
    @Autowired SlaCalculator sla;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate transaction;

    @Test
    void demoResetInTheMiddleOfTheNightStillShowsOverdueAndAtRiskTickets() throws Exception {
        // The next Saturday at 03:00 in Sao Paulo: the service is closed, no working time is passing.
        ZonedDateTime saturdayNight = clock.instant().atZone(ZoneId.of("America/Sao_Paulo"))
                .with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).truncatedTo(ChronoUnit.DAYS).withHour(3);
        clock.advance(Duration.between(clock.instant(), saturdayNight.toInstant()));
        DemoDataSeeder seeder = new DemoDataSeeder(jdbc, userRepository, categories, tickets, comments, history, sla,
                passwordEncoder, clock, transaction);

        assertThat(seeder.resetIfDue()).isTrue();

        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "gestor@ticketflow.demo", "password": "demo1234"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(login, "$.token");
        for (String situation : new String[] {"OVERDUE", "AT_RISK"}) {
            String body = mvc.perform(get("/api/tickets").param("sla", situation).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(readLong(body, "$.totalElements")).as(situation).isPositive();
        }
    }
}
