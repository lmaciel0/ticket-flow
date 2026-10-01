package com.ticketflow.ticket;

import com.ticketflow.category.Category;
import com.ticketflow.common.ApiException;
import com.ticketflow.sla.SlaCalculator;
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
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    private User assignee;

    private Instant createdAt;

    private Instant dueAt;

    private Instant pausedAt;

    private long pausedTotalSeconds;

    private Instant resolvedAt;

    private Boolean slaBreached;

    /** Null for tickets created before the first-response metric existed. */
    private Instant firstResponseDueAt;

    private Instant firstRespondedAt;

    /** Optimistic locking: Hibernate increments it on every update and rejects stale writes. */
    @Version
    private long version;

    protected Ticket() {
        // required by JPA
    }

    public Ticket(String title, String description, Priority priority, Category category, User requester,
            Instant createdAt, SlaCalculator sla) {
        this.title = title.strip();
        this.description = description.strip();
        this.priority = priority;
        this.category = category;
        this.requester = requester;
        this.status = TicketStatus.OPEN;
        this.createdAt = createdAt;
        this.dueAt = sla.dueAt(createdAt, priority, 0);
        this.firstResponseDueAt = sla.firstResponseDueAt(createdAt, priority);
    }

    /** Sets the responsible person. An OPEN ticket starts being worked on. */
    public void assign(User newAssignee, User actor, Instant now, SlaCalculator sla) {
        this.assignee = newAssignee;
        recordFirstResponse(actor, now);
        if (status == TicketStatus.OPEN) {
            changeStatus(TicketStatus.IN_PROGRESS, now, sla);
        }
    }

    /**
     * The first time someone other than the requester takes or answers the ticket. Later calls are
     * ignored, and so are tickets without a deadline (created before the metric existed).
     */
    public void recordFirstResponse(User by, Instant at) {
        if (firstRespondedAt != null || firstResponseDueAt == null || isRequester(by)) {
            return;
        }
        firstRespondedAt = at;
    }

    /** Moves the ticket through its lifecycle and keeps the SLA clock in sync. */
    public void changeStatus(TicketStatus target, Instant now, SlaCalculator sla) {
        if (!status.canTransitionTo(target)) {
            throw ApiException.conflict("Transição de status inválida: " + status + " → " + target + ".");
        }
        if (!status.isClockRunning() && target.isClockRunning()) {
            // Leaving a pause (WAITING_REQUESTER or RESOLVED): the paused time pushes the deadline.
            pausedTotalSeconds += sla.elapsed(pausedAt, now).toSeconds();
            pausedAt = null;
            dueAt = sla.dueAt(createdAt, priority, pausedTotalSeconds);
        } else if (status.isClockRunning() && !target.isClockRunning()) {
            pausedAt = now;
        }
        if (target == TicketStatus.RESOLVED) {
            resolvedAt = now;
            slaBreached = !now.isBefore(dueAt);
        } else if (status == TicketStatus.RESOLVED && target == TicketStatus.IN_PROGRESS) {
            // Reopened: the SLA result is recalculated on the next resolution.
            resolvedAt = null;
            slaBreached = null;
        }
        status = target;
    }

    /** A new priority means a new deadline; time already paused still counts. */
    public void changePriority(Priority newPriority, SlaCalculator sla) {
        this.priority = newPriority;
        this.dueAt = sla.dueAt(createdAt, newPriority, pausedTotalSeconds);
        if (firstRespondedAt == null && firstResponseDueAt != null) {
            // Once answered, the first-response result is final.
            this.firstResponseDueAt = sla.firstResponseDueAt(createdAt, newPriority);
        }
    }

    public void changeCategory(Category newCategory) {
        this.category = newCategory;
    }

    public boolean isRequestedBy(Long userId) {
        return requester.getId().equals(userId);
    }

    public boolean isAssignedTo(Long userId) {
        return assignee != null && assignee.getId().equals(userId);
    }

    /** Same id for entities loaded separately; same instance in unit tests, where nothing has an id yet. */
    private boolean isRequester(User user) {
        return user == requester || (user.getId() != null && user.getId().equals(requester.getId()));
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Priority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Category getCategory() {
        return category;
    }

    public User getRequester() {
        return requester;
    }

    public User getAssignee() {
        return assignee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public long getPausedTotalSeconds() {
        return pausedTotalSeconds;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Boolean getSlaBreached() {
        return slaBreached;
    }

    public Instant getFirstResponseDueAt() {
        return firstResponseDueAt;
    }

    public Instant getFirstRespondedAt() {
        return firstRespondedAt;
    }

    public long getVersion() {
        return version;
    }
}
