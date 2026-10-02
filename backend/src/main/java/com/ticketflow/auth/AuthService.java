package com.ticketflow.auth;

import com.ticketflow.auth.AuthDtos.AuthResponse;
import com.ticketflow.auth.AuthDtos.LoginRequest;
import com.ticketflow.auth.AuthDtos.RegisterRequest;
import com.ticketflow.common.ApiException;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import com.ticketflow.user.UserResponse;
import java.time.Clock;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
            RefreshTokenService refreshTokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("E-mail já cadastrado.");
        }
        // Open sign-up always creates a REQUESTER; only a manager can promote someone later.
        User user = users.save(new User(request.name().strip(), email,
                passwordEncoder.encode(request.password()), Role.REQUESTER, clock.instant()));
        return new AuthResponse(tokenService.issue(user), UserResponse.from(user), refreshTokens.createHandoff(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Same message for "unknown e-mail", "wrong password" and "inactive user":
        // the API must not reveal which e-mails are registered.
        User user = users.findByEmail(normalize(request.email()))
                .filter(User::isActive)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("E-mail ou senha inválidos."));
        return new AuthResponse(tokenService.issue(user), UserResponse.from(user), refreshTokens.createHandoff(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(AuthUser authUser) {
        return UserResponse.from(users.getCurrent(authUser));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
