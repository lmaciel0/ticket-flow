package com.ticketflow.history;

import com.ticketflow.user.UserSummary;
import java.time.Instant;

public record HistoryResponse(Long id, HistoryEventType eventType, String field, String oldValue, String newValue,
        UserSummary actor, Instant occurredAt) {

    public static HistoryResponse from(TicketHistory entry) {
        return new HistoryResponse(entry.getId(), entry.getEventType(), entry.getField(), entry.getOldValue(),
                entry.getNewValue(), UserSummary.from(entry.getActor()), entry.getOccurredAt());
    }
}
