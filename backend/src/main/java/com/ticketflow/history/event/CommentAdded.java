package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

/** Only public comments publish this: an internal note must leave no trace the requester can see. */
public record CommentAdded(Ticket ticket, User actor, Instant at) implements HistoryEvent {
}
