package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Pure SLA math. It receives a Clock instead of calling Instant.now(),
 * so tests can decide what "now" is.
 */
@Component
public class SlaCalculator {

    private final SlaProperties properties;
    private final Clock clock;

    public SlaCalculator(SlaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Duration deadlineFor(Priority priority) {
        return properties.deadlineFor(priority);
    }

    /** A ticket is "at risk" when less than 25% of its deadline is left. */
    public Duration riskWindow(Priority priority) {
        return deadlineFor(priority).dividedBy(4);
    }

    public Instant dueAt(Instant createdAt, Priority priority, long pausedSeconds) {
        return createdAt.plus(deadlineFor(priority)).plusSeconds(pausedSeconds);
    }

    public SlaIndicator indicator(TicketStatus status, Priority priority, Instant dueAt, Boolean slaBreached) {
        if (status == TicketStatus.WAITING_REQUESTER) {
            return SlaIndicator.PAUSED;
        }
        if (status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) {
            return Boolean.TRUE.equals(slaBreached) ? SlaIndicator.BREACHED : SlaIndicator.MET;
        }
        Instant now = clock.instant();
        if (!now.isBefore(dueAt)) {
            return SlaIndicator.OVERDUE;
        }
        if (Duration.between(now, dueAt).compareTo(riskWindow(priority)) < 0) {
            return SlaIndicator.AT_RISK;
        }
        return SlaIndicator.ON_TRACK;
    }
}
