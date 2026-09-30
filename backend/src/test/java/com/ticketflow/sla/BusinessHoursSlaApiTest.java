package com.ticketflow.sla;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Proves the property-driven wiring: enabling business hours changes the deadlines the API returns. */
@TestPropertySource(properties = "app.sla.business-hours.enabled=true")
class BusinessHoursSlaApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Test
    void ticketOpenedOnFridayAfternoonIsDueOnMondayMorning() throws Exception {
        // The next Friday at 17:00 in Sao Paulo; in the future, because the JWT decoder uses the real clock.
        ZonedDateTime friday17h = clock.instant().atZone(ZONE).with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
                .truncatedTo(ChronoUnit.DAYS).withHour(17);
        clock.advance(Duration.between(clock.instant(), friday17h.toInstant()));
        User ana = createUser("Ana", Role.REQUESTER);

        long id = createTicket(ana, "Sistema fora do ar", "CRITICAL");

        // 4h of working time: 1h on Friday + 3h on Monday -> Monday 11:00.
        Instant mondayAt11h = friday17h.plusDays(3).withHour(11).toInstant();
        mvc.perform(get("/api/tickets/{id}", id).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueAt").value(mondayAt11h.toString()))
                .andExpect(jsonPath("$.sla").value("ON_TRACK"));
    }
}
