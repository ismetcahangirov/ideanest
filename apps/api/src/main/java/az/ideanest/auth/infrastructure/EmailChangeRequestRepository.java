package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.EmailChangeRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Outstanding address changes — §4.1's A-12. */
public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, UUID> {

    /**
     * Returns spent and expired rows as well, exactly as
     * {@link VerificationTokenRepository#findByTokenHash} does and for the same
     * reason: an endpoint that cannot tell "this link was already used" from
     * "this is not a link" has to answer both with the same unhelpful sentence.
     */
    Optional<EmailChangeRequest> findByTokenHash(byte[] tokenHash);

    /**
     * Spends one request, if it is still unspent.
     *
     * <p>A conditional update rather than a read followed by a write. Two clicks
     * arriving together would both see an unspent row and both move the account's
     * address; the database decides which wins, and it can only decide when the
     * condition is part of the statement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EmailChangeRequest r
               SET r.consumedAt = :at
             WHERE r.id = :id
               AND r.consumedAt IS NULL
            """)
    int claim(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Retires whatever this account already had outstanding.
     *
     * <p>Asking a second time must invalidate the first link. Two live links mean
     * two addresses the account could move to, and the one the person no longer
     * wants is the one still sitting in a mailbox they may have lost access to.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EmailChangeRequest r
               SET r.consumedAt = :at
             WHERE r.userId = :userId
               AND r.consumedAt IS NULL
            """)
    int consumeOutstanding(@Param("userId") UUID userId, @Param("at") Instant at);

    /**
     * Removes an account's requests outright.
     *
     * <p>Called from {@code AuthAccountSecurity.forget}. These rows are not records
     * of anything worth keeping — each is a capability plus an address, and both
     * halves are exactly what §17.4 anonymisation exists to remove. The foreign key
     * cascades on a real delete; this covers the anonymisation, which is not one.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmailChangeRequest r WHERE r.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
