package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketStatusChanged(Ticket ticket, User actor, Instant at, TicketStatus oldStatus,
        TicketStatus newStatus) implements HistoryEvent {
}
