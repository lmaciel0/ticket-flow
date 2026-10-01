package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketCategoryChanged(Ticket ticket, User actor, Instant at, String oldCategory,
        String newCategory) implements HistoryEvent {
}
