package com.ticketflow.ticket;

import static com.ticketflow.common.ApiExceptionHandler.STALE_VERSION;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final SlaCalculator sla;
    private final Clock clock;

    public TicketService(TicketRepository tickets, CategoryRepository categories, UserRepository users,
            ApplicationEventPublisher events, SlaCalculator sla, Clock clock) {
        this.tickets = tickets;
        this.categories = categories;
        this.users = users;
        this.events = events;
        this.sla = sla;
        this.clock = clock;
    }

    public TicketResponse create(CreateTicketRequest request, AuthUser authUser) {
        User requester = users.getCurrent(authUser);
        Category category = findCategory(request.categoryId());
        Instant now = clock.instant();
        Ticket ticket = tickets.save(new Ticket(request.title(), request.description(), request.priority(),
                category, requester, now, sla));
        events.publishEvent(new TicketCreated(ticket, requester, now));
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse get(Long id, AuthUser authUser) {
        return toResponse(findVisible(id, authUser));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> search(TicketFilter filter, Pageable pageable, AuthUser authUser) {
        var spec = TicketSpecifications.matching(filter, authUser, clock.instant(), sla);
        return PageResponse.from(tickets.findAll(spec, pageable).map(this::toResponse));
    }

    public TicketResponse assign(Long id, AssignRequest request, long expectedVersion, AuthUser authUser) {
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
        requireVersion(ticket, expectedVersion);
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
        ticket.assign(assignee, actor, now, sla);
        events.publishEvent(new TicketAssigned(ticket, actor, now, oldAssignee, assignee.getName()));
        if (ticket.getStatus() != oldStatus) {
            events.publishEvent(new TicketStatusChanged(ticket, actor, now, oldStatus, ticket.getStatus()));
        }
        return flushAndMap(ticket);
    }

    public TicketResponse changeStatus(Long id, ChangeStatusRequest request, long expectedVersion,
            AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        TicketStatus target = request.status();
        if (ticket.getStatus() == TicketStatus.OPEN && target == TicketStatus.IN_PROGRESS) {
            throw ApiException.conflict("Para iniciar o atendimento, atribua o chamado a alguém.");
        }
        requireStatusPermission(ticket, authUser);
        requireVersion(ticket, expectedVersion);

        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        ticket.changeStatus(target, now, sla);
        events.publishEvent(new TicketStatusChanged(ticket, users.getCurrent(authUser), now, oldStatus, target));
        return flushAndMap(ticket);
    }

    public TicketResponse update(Long id, UpdateTicketRequest request, long expectedVersion, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager() && !ticket.isAssignedTo(authUser.id())) {
            throw ApiException.forbidden("Só o responsável pelo chamado ou um gestor pode alterá-lo.");
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não podem ser alterados.");
        }
        requireVersion(ticket, expectedVersion);

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            Priority oldPriority = ticket.getPriority();
            ticket.changePriority(request.priority(), sla);
            events.publishEvent(new TicketPriorityChanged(ticket, actor, now, oldPriority, request.priority()));
        }
        if (request.categoryId() != null && !request.categoryId().equals(ticket.getCategory().getId())) {
            Category category = findCategory(request.categoryId());
            String oldCategory = ticket.getCategory().getName();
            ticket.changeCategory(category);
            events.publishEvent(new TicketCategoryChanged(ticket, actor, now, oldCategory, category.getName()));
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
        return TicketResponse.from(ticket,
                sla.indicator(ticket.getStatus(), ticket.getPriority(), ticket.getDueAt(), ticket.getSlaBreached()),
                sla.firstResponseIndicator(ticket.getStatus(), ticket.getFirstResponseDueAt(),
                        ticket.getFirstRespondedAt()));
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

    /** The client sends, in If-Match, the version it read; if someone changed the ticket since, we refuse. */
    private static void requireVersion(Ticket ticket, long expectedVersion) {
        if (ticket.getVersion() != expectedVersion) {
            throw ApiException.preconditionFailed(STALE_VERSION);
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
