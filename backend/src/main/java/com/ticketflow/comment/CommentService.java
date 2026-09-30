package com.ticketflow.comment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.comment.CommentDtos.CommentRequest;
import com.ticketflow.comment.CommentDtos.CommentResponse;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketService;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommentService {

    private final CommentRepository comments;
    private final TicketService tickets;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final Clock clock;

    public CommentService(CommentRepository comments, TicketService tickets, UserRepository users,
            HistoryRecorder history, SlaCalculator sla, Clock clock) {
        this.comments = comments;
        this.tickets = tickets;
        this.users = users;
        this.history = history;
        this.sla = sla;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long ticketId, AuthUser authUser) {
        tickets.findVisible(ticketId, authUser);
        List<Comment> thread = authUser.isRequester()
                ? comments.findByTicketIdAndInternalFalseOrderByCreatedAtAscIdAsc(ticketId)
                : comments.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId);
        return thread.stream()
                .map(CommentResponse::from)
                .toList();
    }

    public CommentResponse add(Long ticketId, CommentRequest request, AuthUser authUser) {
        Ticket ticket = tickets.findVisible(ticketId, authUser);
        if (request.isInternal() && authUser.isRequester()) {
            throw ApiException.forbidden("Somente a equipe de atendimento pode escrever notas internas.");
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não aceitam comentários.");
        }
        User author = users.getCurrent(authUser);
        Instant now = clock.instant();
        Comment comment = comments.save(new Comment(ticket, author, request.text(), request.isInternal(), now));
        if (request.isInternal()) {
            // The history is visible to the requester, so an internal note must not leave a trace there.
            return CommentResponse.from(comment);
        }
        history.record(ticket, author, HistoryEventType.COMMENT_ADDED, now);

        // The requester answered what the agent asked: the ticket goes back to work automatically.
        if (ticket.getStatus() == TicketStatus.WAITING_REQUESTER && ticket.isRequestedBy(author.getId())) {
            ticket.changeStatus(TicketStatus.IN_PROGRESS, now, sla);
            history.record(ticket, author, HistoryEventType.STATUS_CHANGED, "status",
                    TicketStatus.WAITING_REQUESTER.name(), TicketStatus.IN_PROGRESS.name(), now);
        }
        return CommentResponse.from(comment);
    }
}
