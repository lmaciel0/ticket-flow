package com.ticketflow.history.event;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketPriorityChanged(Ticket ticket, User actor, Instant at, Priority oldPriority,
        Priority newPriority) implements HistoryEvent {
}
