package com.ticketflow.history;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.ticket.TicketService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final TicketService tickets;
    private final TicketHistoryRepository history;

    public HistoryController(TicketService tickets, TicketHistoryRepository history) {
        this.tickets = tickets;
        this.history = history;
    }

    @GetMapping("/api/tickets/{ticketId}/history")
    @Transactional(readOnly = true)
    public List<HistoryResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        tickets.findVisible(ticketId, AuthUser.from(jwt));
        return history.findByTicketIdOrderByOccurredAtAscIdAsc(ticketId).stream()
                .map(HistoryResponse::from)
                .toList();
    }
}
