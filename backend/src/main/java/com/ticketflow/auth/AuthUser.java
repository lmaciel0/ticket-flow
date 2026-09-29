package com.ticketflow.auth;

import com.ticketflow.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling, read from the JWT: "sub" holds the user id and "role" the profile. */
public record AuthUser(Long id, Role role) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(Long.valueOf(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isRequester() {
        return role == Role.REQUESTER;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }
}
