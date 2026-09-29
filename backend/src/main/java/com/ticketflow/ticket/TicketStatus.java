package com.ticketflow.ticket;

import java.util.Set;

/** Ticket lifecycle. The enum itself knows which transitions are valid. */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_REQUESTER,
    RESOLVED,
    CLOSED;

    /** Statuses of tickets still being worked on ("não finalizados"). */
    public static final Set<TicketStatus> ACTIVE = Set.of(OPEN, IN_PROGRESS, WAITING_REQUESTER);

    /** Statuses in which the SLA clock runs. */
    public static final Set<TicketStatus> CLOCK_RUNNING = Set.of(OPEN, IN_PROGRESS);

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case OPEN -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == WAITING_REQUESTER || target == RESOLVED;
            case WAITING_REQUESTER -> target == IN_PROGRESS;
            case RESOLVED -> target == CLOSED || target == IN_PROGRESS;
            case CLOSED -> false;
        };
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isClockRunning() {
        return CLOCK_RUNNING.contains(this);
    }
}
