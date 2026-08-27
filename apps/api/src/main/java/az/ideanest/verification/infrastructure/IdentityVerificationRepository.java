package az.ideanest.verification.infrastructure;

import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.domain.VerificationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Verifications, by the four questions asked of them — issue #105.
 *
 * <p>There is no "every verification" read. A list of everybody the platform has asked for
 * a passport from is a report about its creators rather than a work queue, and §17.4 has no
 * purpose for one.
 */
public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, UUID> {

    /** One creator's current verification. The unique index makes this at most one row. */
    Optional<IdentityVerification> findByUserId(UUID userId);

    /**
     * The staff queue: submitted, oldest first.
     *
     * <p>Oldest first because that is how a queue is worked, and because the retention
     * sweep is counting down on the documents behind each of these — a queue read
     * newest-first would starve exactly the submissions closest to being erased.
     */
    List<IdentityVerification> findByStateOrderByCreatedAtAsc(VerificationState state, Pageable page);

    /** Approvals that have aged past their life, for the expiry sweep. */
    List<IdentityVerification> findByStateAndExpiresAtBefore(VerificationState state, Instant moment);
}
