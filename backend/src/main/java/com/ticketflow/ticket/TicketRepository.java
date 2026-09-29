package com.ticketflow.ticket;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Loads category, requester and assignee in the same query (avoids the N+1 problem in lists). */
    @Override
    @EntityGraph(attributePaths = {"category", "requester", "assignee"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);

    boolean existsByAssigneeIdAndStatusIn(Long assigneeId, Collection<TicketStatus> statuses);
}
