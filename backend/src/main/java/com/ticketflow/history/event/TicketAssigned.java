package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

/** {@code oldAssignee} is null when the ticket had no assignee yet. */
public record TicketAssigned(Ticket ticket, User actor, Instant at, String oldAssignee, String newAssignee)
        implements HistoryEvent {
}
