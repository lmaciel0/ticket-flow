package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One refresh token of a session. A family is every token rotated from the same login. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private UUID familyId;

    private String tokenHash;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant usedAt;

    private Instant revokedAt;

    protected RefreshToken() {
        // required by JPA
    }

    RefreshToken(User user, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void markUsed(Instant at) {
        this.usedAt = at;
    }

    User getUser() {
        return user;
    }

    UUID getFamilyId() {
        return familyId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getUsedAt() {
        return usedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }
}
