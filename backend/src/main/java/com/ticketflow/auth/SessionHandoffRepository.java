package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SessionHandoffRepository extends JpaRepository<SessionHandoff, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SessionHandoff> findByCodeHash(String codeHash);

    @Modifying
    @Query("DELETE FROM SessionHandoff h WHERE h.user = :user AND h.expiresAt < :now")
    void deleteExpired(User user, Instant now);
}
