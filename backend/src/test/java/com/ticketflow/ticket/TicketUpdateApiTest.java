package com.ticketflow.ticket;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

class TicketUpdateApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User diego;
    User carla;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
        ticketId = createTicket(ana, "Impressora", "LOW");
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(0))
                        .content("{\"assigneeId\": %d}".formatted(bruno.getId())))
                .andExpect(status().isOk());
    }

    ResultActions patchTicket(User actor, long version, String json) throws Exception {
        return mvc.perform(patch("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(actor))
                .header("If-Match", etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void assigneeChangesPriorityAndDeadlineFollows() throws Exception {
        patchTicket(bruno, 1, "{\"priority\": \"CRITICAL\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.dueAt").value(clock.instant().plus(Duration.ofHours(4)).toString()))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void managerChangesCategoryAndHistoryRecordsNames() throws Exception {
        patchTicket(carla, 1, "{\"categoryId\": %d}".formatted(categoryId("Software")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.name").value("Software"));

        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$[3].eventType").value("CATEGORY_CHANGED"))
                .andExpect(jsonPath("$[3].oldValue").value("Hardware"))
                .andExpect(jsonPath("$[3].newValue").value("Software"));
    }

    @Test
    void otherAgentsAndRequestersCannotChangeTheTicket() throws Exception {
        patchTicket(diego, 1, "{\"priority\": \"HIGH\"}").andExpect(status().isForbidden());
        patchTicket(ana, 1, "{\"priority\": \"HIGH\"}").andExpect(status().isForbidden());
    }

    @Test
    void staleVersionIsPreconditionFailed() throws Exception {
        patchTicket(bruno, 0, "{\"priority\": \"HIGH\"}").andExpect(status().isPreconditionFailed());
    }

    @Test
    void closedTicketCannotBeChanged() throws Exception {
        long v = readLong(mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(1))
                        .content("{\"status\": \"RESOLVED\"}"))
                .andReturn().getResponse().getContentAsString(), "$.version");
        v = readLong(mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(v))
                        .content("{\"status\": \"CLOSED\"}"))
                .andReturn().getResponse().getContentAsString(), "$.version");

        patchTicket(carla, v, "{\"priority\": \"HIGH\"}")
                .andExpect(status().isConflict());
    }
}
