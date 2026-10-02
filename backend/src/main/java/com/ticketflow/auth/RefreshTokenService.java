package com.ticketflow.auth;

import com.ticketflow.common.ApiException;
import com.ticketflow.user.User;
import com.ticketflow.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Long sessions behind the short access token. Every secret is 256 random bits and only its SHA-256 is stored.
 * A refresh token works once: using it gives a successor in the same family (same expiry). Using it again
 * means two parties hold it, so the whole family is revoked, except within a short grace for two tabs that
 * refreshed at the same moment.
 */
@Service
public class RefreshTokenService {

    public record IssuedRefresh(String token, Instant expiresAt) {
    }

    /** {@code rotated} is null within the reuse grace: the other tab already got the new cookie. */
    public record RefreshResult(String accessToken, UserResponse user, IssuedRefresh rotated) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final SessionHandoffRepository handoffs;
    private final TokenService tokenService;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, SessionHandoffRepository handoffs,
            TokenService tokenService, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.handoffs = handoffs;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
    }

    /** A code for POST /api/auth/session; it also drops this user's expired codes and sessions. */
    @Transactional
    public String createHandoff(User user) {
        Instant now = clock.instant();
        handoffs.deleteExpired(user, now);
        tokens.deleteExpired(user, now);
        String code = newSecret();
        handoffs.save(new SessionHandoff(user, hash(code), now.plus(properties.handoffTtl())));
        return code;
    }

    /** Spends the code and starts a new session family. */
    @Transactional
    public IssuedRefresh redeemHandoff(String code) {
        Instant now = clock.instant();
        SessionHandoff handoff = handoffs.findByCodeHash(hash(code))
                .filter(found -> found.getExpiresAt().isAfter(now))
                .filter(found -> found.getUser().isActive())
                .orElseThrow(() -> ApiException.unauthorized("Código de sessão inválido ou vencido."));
        handoffs.delete(handoff);
        return issue(handoff.getUser(), UUID.randomUUID(), now, now.plus(properties.refreshTtl()));
    }

    /**
     * Empty means "no session": the caller answers 401 and clears the cookie. It never throws for that, so a
     * family revoked here is committed (an exception would roll the revocation back).
     */
    @Transactional
    public Optional<RefreshResult> refresh(String token) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = tokens.findByTokenHash(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        User user = current.getUser();
        if (!user.isActive()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return Optional.empty();
        }
        if (current.getUsedAt() != null) {
            if (now.isAfter(current.getUsedAt().plus(properties.reuseGrace()))) {
                tokens.revokeFamily(current.getFamilyId(), now);
                return Optional.empty();
            }
            return Optional.of(new RefreshResult(tokenService.issue(user), UserResponse.from(user), null));
        }
        current.markUsed(now);
        IssuedRefresh successor = issue(user, current.getFamilyId(), now, current.getExpiresAt());
        return Optional.of(new RefreshResult(tokenService.issue(user), UserResponse.from(user), successor));
    }

    /** Ends the session of this token everywhere. An unknown token is ignored: logging out is always safe. */
    @Transactional
    public void logout(String token) {
        tokens.findByTokenHash(hash(token)).ifPresent(found -> tokens.revokeFamily(found.getFamilyId(), clock.instant()));
    }

    private IssuedRefresh issue(User user, UUID familyId, Instant now, Instant expiresAt) {
        String value = newSecret();
        tokens.save(new RefreshToken(user, familyId, hash(value), now, expiresAt));
        return new IssuedRefresh(value, expiresAt);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
