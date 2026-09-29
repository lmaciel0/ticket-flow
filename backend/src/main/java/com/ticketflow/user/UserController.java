package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.PageResponse;
import com.ticketflow.user.UserService.UpdateUserRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return users.list(page, size);
    }

    @GetMapping("/assignable")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public List<UserSummary> assignable() {
        return users.assignable();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public UserResponse update(@PathVariable Long id, @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return users.update(id, request, AuthUser.from(jwt));
    }
}
