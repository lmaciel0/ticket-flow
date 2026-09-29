package com.ticketflow.history;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Writes history explicitly from the services, inside the same transaction as the change:
 * if the change is rolled back, so is its history entry.
 */
@Component
public class HistoryRecorder {

    private final TicketHistoryRepository history;

    public HistoryRecorder(TicketHistoryRepository history) {
        this.history = history;
    }

    public void record(Ticket ticket, User actor, HistoryEventType type, Instant at) {
        record(ticket, actor, type, null, null, null, at);
    }

    public void record(Ticket ticket, User actor, HistoryEventType type, String field, String oldValue,
            String newValue, Instant at) {
        history.save(new TicketHistory(ticket, actor, at, type, field, oldValue, newValue));
    }
}
