package az.ideanest.user.infrastructure;

import az.ideanest.shared.EmailAddress;
import az.ideanest.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
