package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tickets.create(request, AuthUser.from(jwt));
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
}
