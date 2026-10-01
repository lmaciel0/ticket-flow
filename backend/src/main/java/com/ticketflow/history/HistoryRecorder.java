package com.ticketflow.history;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Writes one history row. The domain services do not call it: they publish events and
 * {@link HistoryEventListener} records them here, before the commit, so the entry lives and dies with the
 * change. The demo seeder calls it directly because it needs back-dated entries.
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
