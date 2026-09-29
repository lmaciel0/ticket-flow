package com.ticketflow.ticket;

import static com.ticketflow.common.ApiExceptionHandler.STALE_VERSION;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final Clock clock;

    public TicketService(TicketRepository tickets, CategoryRepository categories, UserRepository users,
            HistoryRecorder history, SlaCalculator sla, Clock clock) {
        this.tickets = tickets;
        this.categories = categories;
        this.users = users;
        this.history = history;
        this.sla = sla;
        this.clock = clock;
    }

    public TicketResponse create(CreateTicketRequest request, AuthUser authUser) {
        User requester = users.getCurrent(authUser);
        Category category = findCategory(request.categoryId());
        Instant now = clock.instant();
        Ticket ticket = tickets.save(new Ticket(request.title(), request.description(), request.priority(),
                category, requester, now, sla));
        history.record(ticket, requester, HistoryEventType.CREATED, now);
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse get(Long id, AuthUser authUser) {
        return toResponse(findVisible(id, authUser));
    }

    public TicketResponse assign(Long id, AssignRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager()) {
            if (!authUser.id().equals(request.assigneeId())) {
                throw ApiException.forbidden("Atendentes só podem assumir chamados para si mesmos.");
            }
            if (ticket.getStatus() != TicketStatus.OPEN) {
                throw ApiException.conflict("Este chamado já foi assumido.");
            }
        }
        if (!ticket.getStatus().isActive()) {
            throw ApiException.conflict("Chamados resolvidos ou fechados não podem ser atribuídos.");
        }
        requireVersion(ticket, request.version());
        User assignee = users.findById(request.assigneeId())
                .filter(User::canBeAssigned)
                .orElseThrow(() -> ApiException.badRequest("Responsável inválido."));
        if (ticket.isAssignedTo(assignee.getId())) {
            return toResponse(ticket);
        }

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        String oldAssignee = ticket.getAssignee() == null ? null : ticket.getAssignee().getName();
        ticket.assign(assignee, now, sla);
        history.record(ticket, actor, HistoryEventType.ASSIGNED, "assignee", oldAssignee, assignee.getName(), now);
        if (ticket.getStatus() != oldStatus) {
            history.record(ticket, actor, HistoryEventType.STATUS_CHANGED, "status", oldStatus.name(),
                    ticket.getStatus().name(), now);
        }
        return flushAndMap(ticket);
    }

    public TicketResponse changeStatus(Long id, ChangeStatusRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        TicketStatus target = request.status();
        if (ticket.getStatus() == TicketStatus.OPEN && target == TicketStatus.IN_PROGRESS) {
            throw ApiException.conflict("Para iniciar o atendimento, atribua o chamado a alguém.");
        }
        requireStatusPermission(ticket, authUser);
        requireVersion(ticket, request.version());

        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        ticket.changeStatus(target, now, sla);
        history.record(ticket, users.getCurrent(authUser), HistoryEventType.STATUS_CHANGED, "status",
                oldStatus.name(), target.name(), now);
        return flushAndMap(ticket);
    }

    public TicketResponse update(Long id, UpdateTicketRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager() && !ticket.isAssignedTo(authUser.id())) {
            throw ApiException.forbidden("Só o responsável pelo chamado ou um gestor pode alterá-lo.");
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não podem ser alterados.");
        }
        requireVersion(ticket, request.version());

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            String oldPriority = ticket.getPriority().name();
            ticket.changePriority(request.priority(), sla);
            history.record(ticket, actor, HistoryEventType.PRIORITY_CHANGED, "priority", oldPriority,
                    request.priority().name(), now);
        }
        if (request.categoryId() != null && !request.categoryId().equals(ticket.getCategory().getId())) {
            Category category = findCategory(request.categoryId());
            String oldCategory = ticket.getCategory().getName();
            ticket.changeCategory(category);
            history.record(ticket, actor, HistoryEventType.CATEGORY_CHANGED, "category", oldCategory,
                    category.getName(), now);
        }
        return flushAndMap(ticket);
    }

    /**
     * Loads a ticket the caller may see. A requester asking for someone else's ticket gets 404,
     * not 403: the API does not even confirm that the ticket exists.
     */
    public Ticket findVisible(Long id, AuthUser authUser) {
        Ticket ticket = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Chamado não encontrado."));
        if (authUser.isRequester() && !ticket.isRequestedBy(authUser.id())) {
            throw ApiException.notFound("Chamado não encontrado.");
        }
        return ticket;
    }

    public TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.from(ticket, sla.indicator(ticket.getStatus(), ticket.getPriority(),
                ticket.getDueAt(), ticket.getSlaBreached()));
    }

    /** Closing or reopening a RESOLVED ticket is the requester's call; everything else is the assignee's. */
    private void requireStatusPermission(Ticket ticket, AuthUser authUser) {
        if (authUser.isManager()) {
            return;
        }
        boolean allowed = ticket.getStatus() == TicketStatus.RESOLVED
                ? ticket.isRequestedBy(authUser.id())
                : ticket.isAssignedTo(authUser.id());
        if (!allowed) {
            throw ApiException.forbidden("Você não pode mudar o status deste chamado.");
        }
    }

    /** The client sends the version it read; if someone changed the ticket since, we refuse. */
    private static void requireVersion(Ticket ticket, long expectedVersion) {
        if (ticket.getVersion() != expectedVersion) {
            throw ApiException.conflict(STALE_VERSION);
        }
    }

    /** Flushes first so the response carries the new version (Hibernate bumps it on flush). */
    private TicketResponse flushAndMap(Ticket ticket) {
        tickets.flush();
        return toResponse(ticket);
    }

    private Category findCategory(Long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.badRequest("Categoria inválida."));
    }
}
