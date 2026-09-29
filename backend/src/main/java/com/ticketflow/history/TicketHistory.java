package com.ticketflow.history;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ticket_history")
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User actor;

    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    private HistoryEventType eventType;

    private String field;

    private String oldValue;

    private String newValue;

    protected TicketHistory() {
        // required by JPA
    }

    public TicketHistory(Ticket ticket, User actor, Instant occurredAt, HistoryEventType eventType,
            String field, String oldValue, String newValue) {
        this.ticket = ticket;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() {
        return id;
    }

    public User getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public HistoryEventType getEventType() {
        return eventType;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
