package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    public record UpdateUserRequest(Role role, Boolean active) {
    }

    private final UserRepository users;
    private final TicketRepository tickets;

    public UserService(UserRepository users, TicketRepository tickets) {
        this.users = users;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("name", "id"));
        return PageResponse.from(users.findAll(pageable).map(UserResponse::from));
    }

    @Transactional(readOnly = true)
    public List<UserSummary> assignable() {
        return users.findByActiveTrueAndRoleInOrderByNameAsc(List.of(Role.AGENT, Role.MANAGER)).stream()
                .map(UserSummary::from)
                .toList();
    }

    public UserResponse update(Long id, UpdateUserRequest request, AuthUser authUser) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário não encontrado."));
        if (user.isDemo()) {
            throw ApiException.conflict("Contas de demonstração não podem ser alteradas.");
        }
        if (user.getId().equals(authUser.id())) {
            throw ApiException.conflict("Você não pode alterar a sua própria conta.");
        }
        boolean losesTickets = (request.role() == Role.REQUESTER && user.getRole() != Role.REQUESTER)
                || (Boolean.FALSE.equals(request.active()) && user.isActive());
        if (losesTickets && tickets.existsByAssigneeIdAndStatusIn(user.getId(), TicketStatus.ACTIVE)) {
            throw ApiException.conflict(
                    "Este usuário é responsável por chamados em andamento. Reatribua os chamados antes.");
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        return UserResponse.from(user);
    }
}
