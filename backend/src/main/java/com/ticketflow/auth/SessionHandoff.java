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

/** A one-time code that turns a fresh login into a refresh cookie on the site's own domain. */
@Entity
@Table(name = "session_handoffs")
public class SessionHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private String codeHash;

    private Instant expiresAt;

    protected SessionHandoff() {
        // required by JPA
    }

    SessionHandoff(User user, String codeHash, Instant expiresAt) {
        this.user = user;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    User getUser() {
        return user;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
