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

    /**
     * The family, for inspection after a reuse detection.
     *
     * <p><strong>Tie-broken on the identifier, and the tie is not hypothetical.</strong> Two
     * tokens issued in the same instant — which a rotation performed inside one transaction
     * produces, because the clock is read once — order arbitrarily under
     * {@code ORDER BY issued_at} alone, and PostgreSQL is free to return them in whichever
     * order the heap happens to hold them. What that produced was a test that passed alone and
     * failed in a full run, because the rows around it had moved.
     *
     * <p>The identifier is a UUID v7 and therefore in creation order (§7.3), so the second key
     * is the same ordering the first one is reaching for rather than an arbitrary one chosen to
     * make the result stable. That matters for what this read is <em>for</em>: an incident
     * review reading a token family after a theft needs the sequence, and a family listed in an
     * order that changes between two queries is not evidence of anything.
     */
    List<RefreshToken> findBySessionIdOrderByIssuedAtAscIdAsc(UUID sessionId);

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
