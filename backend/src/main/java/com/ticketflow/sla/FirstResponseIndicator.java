package com.ticketflow.sla;

/** Where a ticket stands on its first-response deadline; null in the API means "does not apply". */
public enum FirstResponseIndicator {
    /** Nobody answered yet and there is still time. */
    PENDING,
    /** Nobody answered yet and the deadline has passed. */
    OVERDUE,
    /** Answered before the deadline. */
    MET,
    /** Answered at or after the deadline. */
    BREACHED
}
