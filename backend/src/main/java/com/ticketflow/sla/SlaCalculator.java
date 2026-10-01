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

    public Duration firstResponseDeadlineFor(Priority priority) {
        return properties.firstResponseFor(priority);
    }

    /** Same calendar as the resolution deadline; never paused (a ticket only leaves OPEN by being assigned). */
    public Instant firstResponseDueAt(Instant createdAt, Priority priority) {
        return after(createdAt, firstResponseDeadlineFor(priority));
    }

    /** A ticket is "at risk" when less than 25% of its deadline is left. */
    public Duration riskWindow(Priority priority) {
        return deadlineFor(priority).dividedBy(4);
    }

    /** {@code pausedSeconds} is SLA-clock time (see {@link #elapsed}), so it extends the deadline 1:1. */
    public Instant dueAt(Instant createdAt, Priority priority, long pausedSeconds) {
        return after(createdAt, deadlineFor(priority).plusSeconds(pausedSeconds));
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
        return after(now, riskWindow(priority));
    }

    /** The instant that is {@code amount} of SLA-clock time after {@code start}; the inverse of {@link #ago}. */
    public Instant after(Instant start, Duration amount) {
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

    /**
     * {@code null} means the metric does not apply: the ticket predates it (no deadline), or it was
     * finished with nobody but the requester acting on it.
     */
    @Nullable
    public FirstResponseIndicator firstResponseIndicator(TicketStatus status, @Nullable Instant dueAt,
            @Nullable Instant respondedAt) {
        if (dueAt == null) {
            return null;
        }
        if (respondedAt != null) {
            return respondedAt.isBefore(dueAt) ? FirstResponseIndicator.MET : FirstResponseIndicator.BREACHED;
        }
        if (!status.isActive()) {
            return null;
        }
        return clock.instant().isBefore(dueAt) ? FirstResponseIndicator.PENDING : FirstResponseIndicator.OVERDUE;
    }
}
