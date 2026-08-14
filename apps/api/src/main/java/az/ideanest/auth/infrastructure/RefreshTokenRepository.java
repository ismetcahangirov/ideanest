package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * The lookup every refresh performs. Returns used and expired tokens too:
     * the caller has to be able to tell "already rotated" from "never existed",
     * because only the first one means a copy is in circulation.
     */
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /** The family, for inspection after a reuse detection. */
    List<RefreshToken> findBySessionIdOrderByIssuedAtAsc(UUID sessionId);

    /**
     * Marks a token as exchanged, if nobody has already exchanged it.
     *
     * <p>The condition is the whole point. Two refreshes arriving together both
     * read an unused token; if rotation were a read followed by a write, both
     * would succeed and two live refresh tokens would exist for one session —
     * which is exactly the state reuse detection is meant to make impossible.
     * With the condition in the statement the database picks a winner, and the
     * loser gets zero rows back and is treated as reuse.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.usedAt = :at, t.replacedBy = :replacementId
             WHERE t.id = :id
               AND t.usedAt IS NULL
            """)
    int claimForRotation(
            @Param("id") UUID id, @Param("at") Instant at, @Param("replacementId") UUID replacementId);
}
