package com.ticketflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** First-response SLA through the API: what counts as an answer, who counts, and what the indicator says. */
class FirstResponseSlaApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User diego;
    User carla;

    @BeforeEach
    void createUsers() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    MockHttpServletResponse read(long id) throws Exception {
        return mvc.perform(get("/api/tickets/{id}", id).header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    long version(long id) throws Exception {
        return readLong(read(id).getContentAsString(), "$.version");
    }

    String field(long id, String path) throws Exception {
        return JsonPath.read(read(id).getContentAsString(), path);
    }

    Instant respondedAt(long id) throws Exception {
        String value = field(id, "$.firstRespondedAt");
        return value == null ? null : Instant.parse(value);
    }

    ResultActions assign(User actor, long id, User assignee) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", id)
                        .header("Authorization", bearer(actor))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d}".formatted(assignee.getId())))
                .andExpect(status().isOk());
    }

    void comment(User author, long id, boolean internal) throws Exception {
        mvc.perform(post("/api/tickets/{id}/comments", id)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Estou verificando\", \"internal\": %b}".formatted(internal)))
                .andExpect(status().isCreated());
    }

    @Test
    void newTicketIsPendingWithTheDeadlineOfItsPriority() throws Exception {
        Instant createdAt = clock.instant();
        long id = createTicket(ana, "VPN fora", "HIGH");

        assertThat(Instant.parse(field(id, "$.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofHours(1)));
        assertThat(respondedAt(id)).isNull();
        assertThat(field(id, "$.firstResponse")).isEqualTo("PENDING");
    }

    @Test
    void takingTheTicketInTimeIsMet() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(10));

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value("MET"));

        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void reassigningKeepsTheFirstMoment() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(10));
        assign(bruno, id, bruno);
        Instant first = clock.instant();

        clock.advance(Duration.ofHours(2));
        assign(carla, id, diego).andExpect(jsonPath("$.firstResponse").value("MET"));

        assertThat(respondedAt(id)).isEqualTo(first);
    }

    @Test
    void answeringAfterTheDeadlineIsBreached() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofHours(2));

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value("BREACHED"));
    }

    @Test
    void noAnswerAfterTheDeadlineIsOverdue() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(61));

        assertThat(field(id, "$.firstResponse")).isEqualTo("OVERDUE");
    }

    @Test
    void aPublicTeamCommentIsTheFirstResponseAndChangesTheETagOnlyOnce() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        String before = read(id).getHeader("ETag");
        clock.advance(Duration.ofMinutes(5));

        comment(bruno, id, false);
        MockHttpServletResponse afterFirst = read(id);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
        assertThat((String) JsonPath.read(afterFirst.getContentAsString(), "$.status")).isEqualTo("OPEN");
        assertThat(afterFirst.getHeader("ETag")).isNotEqualTo(before);

        // Later comments do not touch the ticket, so an edit open in another tab keeps working.
        comment(diego, id, false);
        assertThat(read(id).getHeader("ETag")).isEqualTo(afterFirst.getHeader("ETag"));
    }

    @Test
    void internalNotesAndTheRequesterDoNotCount() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");

        comment(bruno, id, true);
        comment(ana, id, false);

        assertThat(respondedAt(id)).isNull();
        assertThat(field(id, "$.firstResponse")).isEqualTo("PENDING");
    }

    @Test
    void anAgentNeverAnswersTheirOwnTicket() throws Exception {
        long id = createTicket(bruno, "Meu monitor", "HIGH");

        comment(bruno, id, false);
        assign(bruno, id, bruno)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.firstResponse").value("PENDING"));
        assertThat(respondedAt(id)).isNull();

        comment(diego, id, false);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void aManagerAssigningTheirOwnTicketIsNotAnAnswer() throws Exception {
        long id = createTicket(carla, "Projetor da sala", "HIGH");

        assign(carla, id, bruno);
        assertThat(respondedAt(id)).isNull();

        comment(bruno, id, false);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void anAgentsOwnTicketResolvedWithoutAnAnswerHasNoIndicator() throws Exception {
        long id = createTicket(bruno, "Meu monitor", "HIGH");
        assign(bruno, id, bruno);

        mvc.perform(post("/api/tickets/{id}/status", id)
                        .header("Authorization", bearer(bruno))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponse").value(nullValue()));
    }

    @Test
    void ticketsFromBeforeTheMetricHaveNoIndicatorAndStaySo() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        jdbc.update("UPDATE tickets SET first_response_due_at = NULL WHERE id = ?", id);

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value(nullValue()));
        mvc.perform(patch("/api/tickets/{id}", id)
                        .header("Authorization", bearer(carla))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"CRITICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponseDueAt").value(nullValue()))
                .andExpect(jsonPath("$.firstRespondedAt").value(nullValue()))
                .andExpect(jsonPath("$.firstResponse").value(nullValue()));
    }
}
