package com.ticketflow.history;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into history entries.
 *
 * <p>BEFORE_COMMIT keeps the entry inside the transaction of the change: if the change is rolled back,
 * so is its history. (With the default AFTER_COMMIT the entry would be written outside it and could
 * diverge from the ticket.) The services that publish the events are all {@code @Transactional}; an event
 * published with no transaction running would be ignored by this listener.
 */
@Component
public class HistoryEventListener {

    private final HistoryRecorder history;

    public HistoryEventListener(HistoryRecorder history) {
        this.history = history;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketCreated event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.CREATED, event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketAssigned event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.ASSIGNED, "assignee",
                event.oldAssignee(), event.newAssignee(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketStatusChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.STATUS_CHANGED, "status",
                event.oldStatus().name(), event.newStatus().name(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketPriorityChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.PRIORITY_CHANGED, "priority",
                event.oldPriority().name(), event.newPriority().name(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketCategoryChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.CATEGORY_CHANGED, "category",
                event.oldCategory(), event.newCategory(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(CommentAdded event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.COMMENT_ADDED, event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(AttachmentAdded event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.ATTACHMENT_ADDED, "attachment", null,
                event.filename(), event.at());
    }
}
