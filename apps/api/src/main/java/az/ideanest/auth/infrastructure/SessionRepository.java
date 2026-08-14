package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.Session;
import az.ideanest.auth.domain.SessionRevocationReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * What the device list shows: sessions that could still be used, newest
     * activity first. A revoked or expired session is not a device the user has
     * to think about.
     */
    @Query("""
            SELECT s FROM Session s
            WHERE s.userId = :userId
              AND s.revokedAt IS NULL
              AND s.expiresAt > :now
            ORDER BY s.lastSeenAt DESC
            """)
    List<Session> findLiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes every live session for a user in one statement.
     *
     * <p>Written as bulk update rather than a loop because of when it is called:
     * a password change, or a detected theft. Loading each session to revoke it
     * individually leaves a window in which some are already dead and some are
     * not, and an attacker holding one of the survivors uses exactly that
     * window. Already-revoked rows are excluded so that the first reason
     * recorded is not overwritten by a later one.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
               SET s.revokedAt = :at, s.revokedReason = :reason
             WHERE s.userId = :userId
               AND s.revokedAt IS NULL
            """)
    int revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("reason") SessionRevocationReason reason,
            @Param("at") Instant at);
}
