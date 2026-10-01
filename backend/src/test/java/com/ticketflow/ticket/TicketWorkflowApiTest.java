package com.ticketflow.ticket;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TicketWorkflowApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    User diego;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions assign(User actor, long ticketId, long assigneeId, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag(version))
                .content("{\"assigneeId\": %d}".formatted(assigneeId)));
    }

    ResultActions changeStatus(User actor, long ticketId, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag(version))
                .content("{\"status\": \"%s\"}".formatted(status)));
    }

    long versionAfter(ResultActions result) throws Exception {
        return readLong(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.version");
    }

    @Test
    void fullLifecycleWithPauseAndReopen() throws Exception {
        long id = createTicket(ana, "Impressora", "HIGH");
        String originalDueAt = clock.instant().plus(Duration.ofHours(8)).toString();

        long v = versionAfter(assign(bruno, id, bruno.getId(), 0)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignee.name").value("Bruno"))
                .andExpect(jsonPath("$.version").value(1)));

        v = versionAfter(changeStatus(bruno, id, "WAITING_REQUESTER", v)
                .andExpect(jsonPath("$.sla").value("PAUSED")));
        clock.advance(Duration.ofHours(2));
        v = versionAfter(changeStatus(bruno, id, "IN_PROGRESS", v)
                .andExpect(jsonPath("$.dueAt").value(
                        java.time.Instant.parse(originalDueAt).plus(Duration.ofHours(2)).toString())));

        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v)
                .andExpect(jsonPath("$.slaBreached").value(false))
                .andExpect(jsonPath("$.sla").value("MET")));
        v = versionAfter(changeStatus(ana, id, "IN_PROGRESS", v)
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.slaBreached").doesNotExist()));
        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v));
        changeStatus(ana, id, "CLOSED", v)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(get("/api/tickets/{id}/history", id).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[1].eventType").value("ASSIGNED"))
                .andExpect(jsonPath("$[1].newValue").value("Bruno"))
                .andExpect(jsonPath("$[2].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[2].oldValue").value("OPEN"))
                .andExpect(jsonPath("$[2].newValue").value("IN_PROGRESS"));
    }

    @Test
    void requesterCannotAssign() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(ana, id, bruno.getId(), 0).andExpect(status().isForbidden());
    }

    @Test
    void agentAssignsOnlyToHimselfAndOnlyOpenTickets() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(bruno, id, diego.getId(), 0).andExpect(status().isForbidden());
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        assign(diego, id, diego.getId(), 1).andExpect(status().isConflict());
    }

    @Test
    void managerReassignsWithoutChangingStatus() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        v = versionAfter(changeStatus(bruno, id, "WAITING_REQUESTER", v));

        assign(carla, id, diego.getId(), v)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.name").value("Diego"))
                .andExpect(jsonPath("$.status").value("WAITING_REQUESTER"));
    }

    @Test
    void assigneeMustBeActiveAgentOrManager() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(carla, id, eva.getId(), 0).andExpect(status().isBadRequest());
        diego.setActive(false);
        userRepository.save(diego);
        assign(carla, id, diego.getId(), 0).andExpect(status().isBadRequest());
    }

    @Test
    void openToInProgressOnlyHappensThroughAssignment() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        changeStatus(carla, id, "IN_PROGRESS", 0).andExpect(status().isConflict());
    }

    @Test
    void invalidTransitionIsConflict() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(bruno, id, "CLOSED", v).andExpect(status().isConflict());
    }

    @Test
    void onlyTheAssigneeOrManagerWorksTheTicket() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(diego, id, "RESOLVED", v).andExpect(status().isForbidden());
        changeStatus(ana, id, "RESOLVED", v).andExpect(status().isForbidden());
        changeStatus(carla, id, "RESOLVED", v).andExpect(status().isOk());
    }

    @Test
    void onlyTheRequesterOrManagerClosesOrReopens() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v));

        changeStatus(bruno, id, "CLOSED", v).andExpect(status().isForbidden());
        changeStatus(eva, id, "CLOSED", v).andExpect(status().isNotFound());
        changeStatus(ana, id, "CLOSED", v).andExpect(status().isOk());
    }

    @Test
    void staleVersionIsPreconditionFailed() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(bruno, id, "RESOLVED", 0)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.detail").value(
                        "O chamado foi alterado por outra pessoa. Recarregue e tente novamente."));
    }

    @Test
    void resolvingLateRecordsBreach() throws Exception {
        long id = createTicket(ana, "Impressora", "CRITICAL");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        clock.advance(Duration.ofHours(5));

        changeStatus(bruno, id, "RESOLVED", v)
                .andExpect(jsonPath("$.slaBreached").value(true))
                .andExpect(jsonPath("$.sla").value("BREACHED"));
    }
}
