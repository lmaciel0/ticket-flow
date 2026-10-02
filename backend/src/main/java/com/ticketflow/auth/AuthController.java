package com.ticketflow.auth;

import com.ticketflow.auth.AuthDtos.AuthResponse;
import com.ticketflow.auth.AuthDtos.LoginRequest;
import com.ticketflow.auth.AuthDtos.RegisterRequest;
import com.ticketflow.auth.AuthDtos.SessionRequest;
import com.ticketflow.user.UserResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookie refreshCookie;

    public AuthController(AuthService authService, RefreshTokenService refreshTokens, RefreshCookie refreshCookie) {
        this.authService = authService;
        this.refreshTokens = refreshTokens;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(AuthUser.from(jwt));
    }

    /** Called through the site (same origin), so the cookie lands on the site's domain. */
    @PostMapping("/session")
    public ResponseEntity<Void> session(
            @CookieValue(name = RefreshCookie.NAME, required = false) String previous,
            @Valid @RequestBody SessionRequest request) {
        // A browser that logs in over another session (another user, or an older login) ends that one first.
        if (previous != null && !previous.isBlank()) {
            refreshTokens.logout(previous);
        }
        RefreshTokenService.IssuedRefresh refresh = refreshTokens.redeemHandoff(request.code());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.issue(refresh)).build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        Optional<RefreshTokenService.RefreshResult> result = token == null || token.isBlank()
                ? Optional.empty()
                : refreshTokens.refresh(token);
        if (result.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "Sessão expirada. Entre novamente.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.clear())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        RefreshTokenService.RefreshResult session = result.get();
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (session.rotated() != null) {
            ok.header(HttpHeaders.SET_COOKIE, refreshCookie.issue(session.rotated()));
        }
        return ok.body(new AuthResponse(session.accessToken(), session.user(), null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        if (token != null && !token.isBlank()) {
            refreshTokens.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear()).build();
    }
}
