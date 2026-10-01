package com.ticketflow.ticket;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

class TicketSearchApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions search(User actor, String query) throws Exception {
        return mvc.perform(get("/api/tickets?" + query).header("Authorization", bearer(actor)))
                .andExpect(status().isOk());
    }

    void assignToBruno(long ticketId) throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(0))
                        .content("{\"assigneeId\": %d}".formatted(bruno.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void requesterOnlyListsOwnTickets() throws Exception {
        createTicket(ana, "Da Ana", "LOW");
        createTicket(eva, "Da Eva", "LOW");

        search(ana, "").andExpect(jsonPath("$.content[*].title", contains("Da Ana")));
        search(carla, "").andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void defaultOrderIsDueAtAscending() throws Exception {
        createTicket(ana, "Baixa", "LOW");
        clock.advance(Duration.ofMinutes(1));
        createTicket(ana, "Critica", "CRITICAL");
        clock.advance(Duration.ofMinutes(1));
        createTicket(ana, "Media", "MEDIUM");

        search(carla, "").andExpect(jsonPath("$.content[*].title", contains("Critica", "Media", "Baixa")));
        search(carla, "sort=createdAt,desc")
                .andExpect(jsonPath("$.content[*].title", contains("Media", "Critica", "Baixa")));
    }

    @Test
    void filtersByStatusPriorityAssigneeAndMine() throws Exception {
        long assigned = createTicket(ana, "Atribuido", "HIGH");
        createTicket(ana, "Aberto", "LOW");
        assignToBruno(assigned);

        search(carla, "status=OPEN").andExpect(jsonPath("$.content[*].title", contains("Aberto")));
        search(carla, "status=OPEN&status=IN_PROGRESS").andExpect(jsonPath("$.totalElements").value(2));
        search(carla, "priority=HIGH").andExpect(jsonPath("$.content[*].title", contains("Atribuido")));
        search(carla, "assigneeId=" + bruno.getId()).andExpect(jsonPath("$.totalElements").value(1));
        search(bruno, "mine=true").andExpect(jsonPath("$.content[*].title", contains("Atribuido")));
    }

    @Test
    void searchesTitleAndDescriptionCaseInsensitivelyAndLiterally() throws Exception {
        createTicket(ana, "Impressora travada", "LOW");
        createTicket(ana, "Desconto de 100% no boleto", "LOW");
        createTicket(ana, "Desconto de 1000 reais", "LOW");

        search(carla, "q=IMPRESSORA").andExpect(jsonPath("$.content[*].title", contains("Impressora travada")));
        mvc.perform(get("/api/tickets").param("q", "100%").header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.content[*].title", contains("Desconto de 100% no boleto")));
        search(carla, "q=detalhes").andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void filtersBySlaSituation() throws Exception {
        createTicket(ana, "Critico", "CRITICAL");
        createTicket(ana, "Alto", "HIGH");
        createTicket(ana, "Baixo", "LOW");
        clock.advance(Duration.ofMinutes(6 * 60 + 30));
        // Now: CRITICAL (4h) is overdue, HIGH (8h) has 1h30 left (< 2h: at risk), LOW (72h) is on track.

        search(carla, "sla=OVERDUE").andExpect(jsonPath("$.content[*].title", contains("Critico")));
        search(carla, "sla=AT_RISK").andExpect(jsonPath("$.content[*].title", contains("Alto")));
        search(carla, "").andExpect(jsonPath("$.content[*].sla", containsInAnyOrder("OVERDUE", "AT_RISK", "ON_TRACK")));
    }

    @Test
    void paginatesWithStableOrderAndCapsPageSize() throws Exception {
        for (int i = 1; i <= 3; i++) {
            createTicket(ana, "Chamado " + i, "LOW");
        }

        search(carla, "size=2&page=0")
                .andExpect(jsonPath("$.content[*].title", contains("Chamado 1", "Chamado 2")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        search(carla, "size=2&page=1").andExpect(jsonPath("$.content[*].title", contains("Chamado 3")));
        search(carla, "size=1000").andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rejectsUnknownSortFieldAndInvalidEnum() throws Exception {
        mvc.perform(get("/api/tickets?sort=title,asc").header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets?status=DONE").header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
    }
}
