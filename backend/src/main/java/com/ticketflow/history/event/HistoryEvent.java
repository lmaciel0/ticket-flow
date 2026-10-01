package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

/**
 * Something that happened to a ticket and that the system may react to (today: write the history;
 * tomorrow: send an e-mail, call a webhook). Sealed, so the full list of events is visible in one place.
 */
public sealed interface HistoryEvent
        permits TicketCreated, TicketAssigned, TicketStatusChanged, TicketPriorityChanged,
                TicketCategoryChanged, CommentAdded, AttachmentAdded {

    Ticket ticket();

    User actor();

    Instant at();
}
