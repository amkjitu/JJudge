package com.codearena.api.repository;

import com.codearena.api.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Query("""
            SELECT rt FROM RefreshToken rt
            JOIN FETCH rt.user
            WHERE rt.tokenHash = :tokenHash
            """)
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Revokes every live token for a user in one statement. Used by logout-everywhere and, more
     * importantly, by reuse detection: if a rotated token reappears, the safe assumption is
     * that the user's token chain is in an attacker's hands.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken rt SET rt.revokedAt = :now
            WHERE rt.user.id = :userId AND rt.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    long countByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Housekeeping: tokens that are past their expiry can never be redeemed again, so the rows
     * are only taking up space. Called on a schedule rather than on the request path.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
