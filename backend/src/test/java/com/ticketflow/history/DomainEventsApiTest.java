package com.ticketflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
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
                .content("{\"assigneeId\": %d, \"version\": %d}".formatted(assigneeId, version)));
    }

    ResultActions patchTicket(User actor, long ticketId, String json) throws Exception {
        return mvc.perform(patch("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
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
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
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

        patchTicket(carla, id, "{\"priority\": \"HIGH\", \"categoryId\": %d, \"version\": 0}"
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

        patchTicket(carla, id, "{\"priority\": \"LOW\", \"categoryId\": %d, \"version\": 0}"
                .formatted(categoryId("Hardware"))).andExpect(status().isOk());

        assertThat(recorder.received).isEmpty();
    }
}
