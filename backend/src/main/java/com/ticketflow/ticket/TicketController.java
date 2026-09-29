package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Set<String> SORTABLE_FIELDS = Set.of("dueAt", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tickets.create(request, AuthUser.from(jwt));
    }

    @GetMapping
    public PageResponse<TicketResponse> search(
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) TicketFilter.SlaFilter sla,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "dueAt,asc") String sort,
            @AuthenticationPrincipal Jwt jwt) {
        TicketFilter filter = new TicketFilter(status, priority, categoryId, assigneeId, sla, q, mine);
        return tickets.search(filter, pageable(page, size, sort), AuthUser.from(jwt));
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return tickets.get(id, AuthUser.from(jwt));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.update(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.assign(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/status")
    public TicketResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.changeStatus(id, request, AuthUser.from(jwt));
    }

    /** Only whitelisted fields can be sorted; the id is a tie-breaker so pages never overlap. */
    private static Pageable pageable(int page, int size, String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].strip();
        if (!SORTABLE_FIELDS.contains(field)) {
            throw ApiException.badRequest("Ordenação inválida. Use dueAt ou createdAt.");
        }
        Sort.Direction direction = parts.length > 1
                ? Sort.Direction.fromOptionalString(parts[1].strip())
                        .orElseThrow(() -> ApiException.badRequest("Direção de ordenação inválida. Use asc ou desc."))
                : Sort.Direction.ASC;
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(direction, field).and(Sort.by("id")));
    }
}
