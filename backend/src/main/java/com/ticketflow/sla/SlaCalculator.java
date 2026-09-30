package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pure SLA math. It receives a Clock instead of calling Instant.now(),
 * so tests can decide what "now" is. With a {@link BusinessCalendar} the SLA clock only runs during
 * working hours; without one it runs around the clock.
 */
@Component
public class SlaCalculator {

    private final SlaProperties properties;
    private final Clock clock;
    private final BusinessCalendar calendar;

    public SlaCalculator(SlaProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    @Autowired
    public SlaCalculator(SlaProperties properties, Clock clock, @Nullable BusinessCalendar calendar) {
        this.properties = properties;
        this.clock = clock;
        this.calendar = calendar;
    }

    public Duration deadlineFor(Priority priority) {
        return properties.deadlineFor(priority);
    }

    /** A ticket is "at risk" when less than 25% of its deadline is left. */
    public Duration riskWindow(Priority priority) {
        return deadlineFor(priority).dividedBy(4);
    }

    /** {@code pausedSeconds} is SLA-clock time (see {@link #elapsed}), so it extends the deadline 1:1. */
    public Instant dueAt(Instant createdAt, Priority priority, long pausedSeconds) {
        return advance(createdAt, deadlineFor(priority).plusSeconds(pausedSeconds));
    }

    /** SLA-clock time between two instants: wall time, or only working hours when a calendar is set. */
    public Duration elapsed(Instant from, Instant to) {
        return calendar == null ? Duration.between(from, to) : calendar.between(from, to);
    }

    /** The instant that is {@code amount} of SLA-clock time before {@code now}; used to age the demo tickets. */
    public Instant ago(Instant now, Duration amount) {
        return calendar == null ? now.minus(amount) : calendar.minus(now, amount);
    }

    /** A running ticket due before this instant has less than its risk window left. */
    public Instant atRiskBefore(Instant now, Priority priority) {
        return advance(now, riskWindow(priority));
    }

    private Instant advance(Instant start, Duration amount) {
        return calendar == null ? start.plus(amount) : calendar.plus(start, amount);
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
        if (elapsed(now, dueAt).compareTo(riskWindow(priority)) < 0) {
            return SlaIndicator.AT_RISK;
        }
        return SlaIndicator.ON_TRACK;
    }
}
