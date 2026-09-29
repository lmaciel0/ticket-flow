package com.ticketflow.dashboard;

import com.ticketflow.ticket.TicketStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
        LocalDate from,
        LocalDate to,
        Map<TicketStatus, Long> ticketsByStatus,
        long overdueNow,
        long resolvedInPeriod,
        Double slaMetPercentage,
        Double averageResolutionHours,
        List<CategoryCount> openedByCategory,
        List<AgentStats> agents,
        List<DailyCount> daily) {

    public record CategoryCount(String category, long count) {
    }

    public record AgentStats(Long id, String name, long activeAssigned, long resolvedInPeriod) {
    }

    public record DailyCount(LocalDate date, long opened, long resolved) {
    }
}
