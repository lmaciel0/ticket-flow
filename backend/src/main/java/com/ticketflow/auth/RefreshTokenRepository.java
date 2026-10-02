package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Locks the row: two refreshes of the same token wait for each other instead of both rotating it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :at WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    void revokeFamily(UUID familyId, Instant at);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.user = :user AND t.expiresAt < :now")
    void deleteExpired(User user, Instant now);
}
