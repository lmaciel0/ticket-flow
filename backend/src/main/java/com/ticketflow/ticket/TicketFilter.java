package com.ticketflow.ticket;

import java.util.List;

/** Optional filters of GET /api/tickets. Null (or empty) means "do not filter by this". */
public record TicketFilter(
        List<TicketStatus> statuses,
        Priority priority,
        Long categoryId,
        Long assigneeId,
        SlaFilter sla,
        String query,
        boolean mine) {

    public enum SlaFilter {
        OVERDUE,
        AT_RISK
    }
}
