package az.ideanest.user.infrastructure;

import az.ideanest.shared.EmailAddress;
import az.ideanest.user.domain.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Users, by the things they are actually looked up by.
 *
 * <p>Every finder here excludes soft-deleted rows. A deleted account must not
 * be signed in to, must not appear on a profile page, and must not be handed a
 * password reset; making each caller remember that is how one of the three
 * eventually gets missed.
 *
 * <p>Lookups take {@link EmailAddress} rather than {@code String} so that the
 * parameter is normalised the same way the stored value was. A raw string would
 * be bound as {@code varchar} and compared as text, and a sign-in typed with a
 * capital letter would find nothing.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByEmailAndDeletedAtIsNull(EmailAddress email);

    Optional<User> findBySlugAndDeletedAtIsNull(String slug);

    /**
     * Includes deleted accounts, unlike the finders. An address belonging to a
     * closed account must not be registered again: the new owner would receive
     * password resets and receipts for the old account's history.
     */
    boolean existsByEmail(EmailAddress email);

    boolean existsBySlug(String slug);

    /**
     * Accounts whose grace period has elapsed and which have not been
     * anonymised yet, oldest first.
     *
     * <p>Identifiers rather than entities, and a page rather than everything:
     * the job reloads each one inside its own transaction, so loading the whole
     * backlog here would hold a result set open for the length of the batch and
     * hand the next instance a list that is already stale.
     *
     * <p>Note what is <em>not</em> excluded: a soft-deleted row. Deletion is
     * requested without soft-deleting, but an account banned or removed by some
     * other route still has a request to honour.
     */
    @Query("""
            SELECT u.id FROM User u
            WHERE u.anonymisedAt IS NULL
              AND u.deletionScheduledAt IS NOT NULL
              AND u.deletionScheduledAt <= :now
            ORDER BY u.deletionScheduledAt
            """)
    List<UUID> findDueForAnonymisation(@Param("now") Instant now, Pageable batch);

    /**
     * The row, locked until the transaction ends.
     *
     * <p>The one place a lock is taken. Anonymisation reads the row, decides
     * whether it is due, and overwrites it; two instances running the job at the
     * same moment would otherwise both read a due row and both proceed. With the
     * lock the second one waits, re-reads, finds the work already done, and does
     * nothing — which is what "safe on more than one instance" has to mean in
     * practice rather than in a comment.
     *
     * <p>Ignores {@code deleted_at}, unlike every other finder here: this is the
     * one operation whose whole purpose is to act on an account nobody may sign
     * in to.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
