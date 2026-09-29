package com.ticketflow.comment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CommentApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        ticketId = createTicket(ana, "Impressora", "LOW");
    }

    ResultActions comment(User author, String text) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/comments", ticketId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"%s\"}".formatted(text)));
    }

    ResultActions changeStatus(User actor, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\", \"version\": %d}".formatted(status, version)));
    }

    void assignToBrunoAndAskRequester() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        changeStatus(bruno, "WAITING_REQUESTER", 1).andExpect(status().isOk());
    }

    @Test
    void requesterAndAgentCommentAndBothSeeTheThread() throws Exception {
        comment(ana, "Olá").andExpect(status().isCreated()).andExpect(jsonPath("$.author.name").value("Ana"));
        comment(bruno, "Vou verificar").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}/comments", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].text").value("Vou verificar"));
    }

    @Test
    void otherRequestersGet404() throws Exception {
        comment(eva, "Intrometida").andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/comments", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankCommentIsRejected() throws Exception {
        comment(ana, "   ").andExpect(status().isBadRequest());
    }

    @Test
    void requesterCommentResumesTicketWaitingForHer() throws Exception {
        assignToBrunoAndAskRequester();

        comment(ana, "Segue o print").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[-1].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[-1].actor.name").value("Ana"))
                .andExpect(jsonPath("$[-2].eventType").value("COMMENT_ADDED"));
    }

    @Test
    void agentCommentDoesNotResumeTheTicket() throws Exception {
        assignToBrunoAndAskRequester();

        comment(bruno, "Lembrete: preciso do print").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.status").value("WAITING_REQUESTER"));
    }

    @Test
    void closedTicketRejectsComments() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        changeStatus(bruno, "RESOLVED", 1).andExpect(status().isOk());
        changeStatus(ana, "CLOSED", 2).andExpect(status().isOk());

        comment(ana, "Mais uma coisa").andExpect(status().isConflict());
    }
}
