package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record AttachmentAdded(Ticket ticket, User actor, Instant at, String filename) implements HistoryEvent {
}
