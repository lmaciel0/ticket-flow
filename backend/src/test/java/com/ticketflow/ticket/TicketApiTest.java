package com.ticketflow.ticket;

import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TicketApiTest extends IntegrationTest {

    @Test
    void requesterCreatesTicketWithDeadlineFromPriority() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  Sem acesso à VPN ", "description": "Erro 809", "priority": "CRITICAL",
                                 "categoryId": %d}
                                """.formatted(categoryId("Acesso"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sem acesso à VPN"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.category.name").value("Acesso"))
                .andExpect(jsonPath("$.requester.name").value("Ana"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.dueAt").value(clock.instant().plusSeconds(4 * 3600).toString()))
                .andExpect(jsonPath("$.sla").value("ON_TRACK"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createValidatesFieldsAndCategory() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "", "description": "x", "priority": null, "categoryId": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("title")))
                .andExpect(jsonPath("$.errors", hasKey("priority")));
        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "description": "x", "priority": "LOW", "categoryId": 999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Categoria inválida."));
    }

    @Test
    void unknownPriorityIsBadRequestNotServerError() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "description": "x", "priority": "URGENTE", "categoryId": 1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requesterCannotSeeSomeoneElsesTicket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        User eva = createUser("Eva", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentsAndManagersSeeAnyTicket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(createUser("Bruno", Role.AGENT))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(createUser("Carla", Role.MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void missingTicketIs404() throws Exception {
        User bruno = createUser("Bruno", Role.AGENT);

        mvc.perform(get("/api/tickets/{id}", 999).header("Authorization", bearer(bruno)))
                .andExpect(status().isNotFound());
    }

    @Test
    void creationIsRecordedInHistory() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$[0].actor.name").value("Ana"));
    }
}
