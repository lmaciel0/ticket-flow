package com.ticketflow.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class DashboardApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    void assignAndResolve(long ticketId, Duration workTime) throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(0))
                        .content("{\"assigneeId\": %d}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        clock.advance(workTime);
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(1))
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void summarizesCurrentStateAndPeriod() throws Exception {
        long fast = createTicket(ana, "Rapido", "CRITICAL");
        long slow = createTicket(ana, "Lento", "CRITICAL");
        createTicket(ana, "Parado", "CRITICAL");
        assignAndResolve(fast, Duration.ofHours(2));
        assignAndResolve(slow, Duration.ofHours(4));
        // Resolution times: 2h (met) and 6h (breached: 4h deadline). "Parado" is now 6h old: overdue.

        mvc.perform(get("/api/dashboard").header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketsByStatus.OPEN").value(1))
                .andExpect(jsonPath("$.ticketsByStatus.RESOLVED").value(2))
                .andExpect(jsonPath("$.ticketsByStatus.CLOSED").value(0))
                .andExpect(jsonPath("$.overdueNow").value(1))
                .andExpect(jsonPath("$.resolvedInPeriod").value(2))
                .andExpect(jsonPath("$.slaMetPercentage").value(50.0))
                .andExpect(jsonPath("$.averageResolutionHours").value(4.0))
                .andExpect(jsonPath("$.openedByCategory[1].category").value("Hardware"))
                .andExpect(jsonPath("$.openedByCategory[1].count").value(3))
                .andExpect(jsonPath("$.agents[0].name").value("Bruno"))
                .andExpect(jsonPath("$.agents[0].resolvedInPeriod").value(2))
                .andExpect(jsonPath("$.agents[1].name").value("Carla"))
                .andExpect(jsonPath("$.daily.length()").value(30));
    }

    @Test
    void emptyPeriodHasNullRates() throws Exception {
        mvc.perform(get("/api/dashboard").header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.slaMetPercentage").doesNotExist())
                .andExpect(jsonPath("$.averageResolutionHours").doesNotExist());
    }

    @Test
    void countsDaysInTheConfiguredTimeZone() throws Exception {
        createTicket(ana, "Hoje", "LOW");
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("America/Sao_Paulo"));

        mvc.perform(get("/api/dashboard").param("from", today.toString()).param("to", today.toString())
                        .header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.daily[0].date").value(today.toString()))
                .andExpect(jsonPath("$.daily[0].opened").value(1));
    }

    @Test
    void rejectsInvalidPeriods() throws Exception {
        mvc.perform(get("/api/dashboard").param("from", "2026-01-10").param("to", "2026-01-01")
                        .header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard").param("from", "2026-01-01").param("to", "2026-06-01")
                        .header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyManagers() throws Exception {
        mvc.perform(get("/api/dashboard").header("Authorization", bearer(bruno)))
                .andExpect(status().isForbidden());
    }
}
