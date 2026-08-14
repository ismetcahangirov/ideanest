package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.TwoFactorSecret;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** TOTP enrolments, keyed by the user they belong to. */
public interface TwoFactorSecretRepository extends JpaRepository<TwoFactorSecret, UUID> {

    /**
     * A confirmed enrolment, and nothing else.
     *
     * <p>Separate from {@code findById} because the two questions are different
     * and confusing them is the bug this feature can least afford: a row exists
     * as soon as somebody starts enrolling, and treating that as "two-factor is
     * on" would demand a code from a user who has never successfully produced
     * one.
     */
    Optional<TwoFactorSecret> findByUserIdAndConfirmedAtIsNotNull(UUID userId);
}
