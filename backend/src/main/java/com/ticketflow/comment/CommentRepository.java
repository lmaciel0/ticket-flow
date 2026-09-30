package com.ticketflow.comment;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = "author")
    List<Comment> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);

    /** What the requester is allowed to read: internal notes are left out. */
    @EntityGraph(attributePaths = "author")
    List<Comment> findByTicketIdAndInternalFalseOrderByCreatedAtAscIdAsc(Long ticketId);
}
