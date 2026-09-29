package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.sla.SlaCalculator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Builds the WHERE clause of the ticket list from the optional filters (JPA Criteria API). */
public final class TicketSpecifications {

    private TicketSpecifications() {
    }

    public static Specification<Ticket> matching(TicketFilter filter, AuthUser authUser, Instant now,
            SlaCalculator sla) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (authUser.isRequester()) {
                predicates.add(cb.equal(root.get("requester").get("id"), authUser.id()));
            } else if (filter.mine()) {
                predicates.add(cb.equal(root.get("assignee").get("id"), authUser.id()));
            }
            if (filter.statuses() != null && !filter.statuses().isEmpty()) {
                predicates.add(root.get("status").in(filter.statuses()));
            }
            if (filter.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), filter.priority()));
            }
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }
            if (filter.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignee").get("id"), filter.assigneeId()));
            }
            if (filter.query() != null && !filter.query().isBlank()) {
                String pattern = "%" + escapeLike(filter.query().strip().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (filter.sla() != null) {
                predicates.add(slaPredicate(filter.sla(), root, cb, now, sla));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Same rules as SlaCalculator.indicator, written as SQL so the database does the filtering. */
    private static Predicate slaPredicate(TicketFilter.SlaFilter slaFilter, Root<Ticket> root, CriteriaBuilder cb,
            Instant now, SlaCalculator sla) {
        Predicate running = root.get("status").in(TicketStatus.CLOCK_RUNNING);
        if (slaFilter == TicketFilter.SlaFilter.OVERDUE) {
            return cb.and(running, cb.lessThanOrEqualTo(root.get("dueAt"), now));
        }
        // AT_RISK: not overdue yet, but less than 25% of *this priority's* deadline is left.
        List<Predicate> perPriority = new ArrayList<>();
        for (Priority priority : Priority.values()) {
            perPriority.add(cb.and(
                    cb.equal(root.get("priority"), priority),
                    cb.lessThan(root.get("dueAt"), now.plus(sla.riskWindow(priority)))));
        }
        return cb.and(running, cb.greaterThan(root.get("dueAt"), now),
                cb.or(perPriority.toArray(Predicate[]::new)));
    }

    /** "100%" must search for the text "100%", not "100 followed by anything". */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
