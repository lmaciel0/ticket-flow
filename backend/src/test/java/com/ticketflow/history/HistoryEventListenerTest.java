package com.ticketflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Instant;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HistoryEventListenerTest extends IntegrationTest {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TicketRepository ticketRepository;

    long ticketId;
    long actorId;

    @BeforeEach
    void setUp() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        User bruno = createUser("Bruno", Role.AGENT);
        actorId = bruno.getId();
        ticketId = createTicket(ana, "Impressora", "LOW");
        // Creating the ticket already wrote a CREATED entry; start every test from an empty history.
        jdbc.update("DELETE FROM ticket_history");
    }

    /** Runs the work inside one transaction, with the ticket and the actor loaded in that transaction. */
    private void inTransaction(BiConsumer<Ticket, User> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> work.accept(
                ticketRepository.findById(ticketId).orElseThrow(),
                userRepository.findById(actorId).orElseThrow()));
    }

    @Test
    void eachEventBecomesItsHistoryEntryInPublicationOrder() {
        Instant at = clock.instant();

        inTransaction((ticket, actor) -> {
            events.publishEvent(new TicketCreated(ticket, actor, at));
            events.publishEvent(new TicketAssigned(ticket, actor, at, null, "Bruno"));
            events.publishEvent(new TicketStatusChanged(ticket, actor, at, TicketStatus.OPEN,
                    TicketStatus.IN_PROGRESS));
            events.publishEvent(new TicketPriorityChanged(ticket, actor, at, Priority.LOW, Priority.HIGH));
            events.publishEvent(new TicketCategoryChanged(ticket, actor, at, "Hardware", "Software"));
            events.publishEvent(new CommentAdded(ticket, actor, at));
            events.publishEvent(new AttachmentAdded(ticket, actor, at, "relatorio.pdf"));
        });

        assertThat(jdbc.queryForList(
                "SELECT event_type, field, old_value, new_value FROM ticket_history ORDER BY id"))
                .extracting(row -> row.get("event_type"), row -> row.get("field"),
                        row -> row.get("old_value"), row -> row.get("new_value"))
                .containsExactly(
                        tuple("CREATED", null, null, null),
                        tuple("ASSIGNED", "assignee", null, "Bruno"),
                        tuple("STATUS_CHANGED", "status", "OPEN", "IN_PROGRESS"),
                        tuple("PRIORITY_CHANGED", "priority", "LOW", "HIGH"),
                        tuple("CATEGORY_CHANGED", "category", "Hardware", "Software"),
                        tuple("COMMENT_ADDED", null, null, null),
                        tuple("ATTACHMENT_ADDED", "attachment", null, "relatorio.pdf"));
    }

    @Test
    void rollbackTakesTheHistoryEntryWithIt() {
        assertThatThrownBy(() -> inTransaction((ticket, actor) -> {
            events.publishEvent(new CommentAdded(ticket, actor, clock.instant()));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history", Long.class)).isZero();
    }
}
