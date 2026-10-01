package com.ticketflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import com.ticketflow.history.event.HistoryEvent;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Proves the decoupling: the services publish events, and a listener that none of them knows about
 * receives them. Adding behavior (e-mail, webhook) is exactly this: one more listener, no service edited.
 */
class DomainEventsApiTest extends IntegrationTest {

    static class EventRecorder {

        final List<HistoryEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(HistoryEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderConfiguration {

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    @Autowired EventRecorder recorder;

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void setUp() {
        recorder.received.clear();
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions assign(User actor, long ticketId, long assigneeId, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag(version))
                .content("{\"assigneeId\": %d}".formatted(assigneeId)));
    }

    ResultActions patchTicket(User actor, long ticketId, long version, String json) throws Exception {
        return mvc.perform(patch("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(actor))
                .header("If-Match", etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    ResultActions comment(User author, long ticketId, String text, boolean internal) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/comments", ticketId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"%s\", \"internal\": %s}".formatted(text, internal)));
    }

    ResultActions changeStatus(User actor, long ticketId, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", etag(version))
                .content("{\"status\": \"%s\"}".formatted(status)));
    }

    List<String> historyEventTypes(long ticketId) {
        return jdbc.queryForList("SELECT event_type FROM ticket_history WHERE ticket_id = ? ORDER BY id",
                String.class, ticketId);
    }

    @Test
    void creatingATicketPublishesTicketCreatedAndTheHistoryListensToIt() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(TicketCreated.class, event -> {
            assertThat(event.ticket().getId()).isEqualTo(id);
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
        });
        assertThat(historyEventTypes(id)).containsExactly("CREATED");
    }

    @Test
    void assigningPublishesAssignedThenStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOfSatisfying(TicketAssigned.class, event -> {
            assertThat(event.oldAssignee()).isNull();
            assertThat(event.newAssignee()).isEqualTo("Bruno");
        });
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.OPEN);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        });
    }

    @Test
    void assigningToTheCurrentAssigneeAgainPublishesNothing() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        recorder.received.clear();

        assign(carla, id, bruno.getId(), 1).andExpect(status().isOk());

        assertThat(recorder.received).isEmpty();
    }

    @Test
    void changingStatusPublishesStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        recorder.received.clear();

        mvc.perform(post("/api/tickets/{id}/status", id)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag(1))
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(bruno.getId());
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.RESOLVED);
        });
    }

    @Test
    void updatingPriorityAndCategoryPublishesBothInOrder() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        patchTicket(carla, id, 0, "{\"priority\": \"HIGH\", \"categoryId\": %d}"
                .formatted(categoryId("Software"))).andExpect(status().isOk());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOfSatisfying(TicketPriorityChanged.class, event -> {
            assertThat(event.oldPriority()).isEqualTo(Priority.LOW);
            assertThat(event.newPriority()).isEqualTo(Priority.HIGH);
        });
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketCategoryChanged.class, event -> {
            assertThat(event.oldCategory()).isEqualTo("Hardware");
            assertThat(event.newCategory()).isEqualTo("Software");
        });
    }

    @Test
    void updatingWithTheCurrentValuesPublishesNothing() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        patchTicket(carla, id, 0, "{\"priority\": \"LOW\", \"categoryId\": %d}"
                .formatted(categoryId("Hardware"))).andExpect(status().isOk());

        assertThat(recorder.received).isEmpty();
    }

    @Test
    void publicCommentPublishesCommentAdded() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        comment(ana, id, "Olá", false).andExpect(status().isCreated());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(CommentAdded.class,
                event -> assertThat(event.actor().getId()).isEqualTo(ana.getId()));
        assertThat(historyEventTypes(id)).containsExactly("CREATED", "COMMENT_ADDED");
    }

    @Test
    void internalNotePublishesNothingAndLeavesNoHistory() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        comment(bruno, id, "Suspeito do cabo", true).andExpect(status().isCreated());

        assertThat(recorder.received).isEmpty();
        assertThat(historyEventTypes(id)).containsExactly("CREATED");
    }

    @Test
    void requesterAnswerPublishesCommentAddedThenStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        changeStatus(bruno, id, "WAITING_REQUESTER", 1).andExpect(status().isOk());
        recorder.received.clear();

        comment(ana, id, "Segue o print", false).andExpect(status().isCreated());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOf(CommentAdded.class);
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.WAITING_REQUESTER);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        });
    }

    @Test
    void uploadPublishesAttachmentAdded() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();
        MockMultipartFile file = new MockMultipartFile("file", "relatório.pdf", "application/octet-stream",
                "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/tickets/{id}/attachments", id).file(file).header("Authorization", bearer(ana)))
                .andExpect(status().isCreated());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(AttachmentAdded.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
            assertThat(event.filename()).isEqualTo("relatório.pdf");
        });
    }
}
