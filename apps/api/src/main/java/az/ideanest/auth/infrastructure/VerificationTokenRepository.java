package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    /**
     * Returns spent and expired tokens as well. A redemption endpoint that
     * cannot distinguish "this link was already used" from "this link is not a
     * link" has to answer both with the same unhelpful error.
     */
    Optional<VerificationToken> findByTokenHash(byte[] tokenHash);

    /**
     * Invalidates outstanding tokens of one purpose for one user.
     *
     * <p>Issuing a second reset link must retire the first: two live links
     * double the window in which a forwarded or leaked email still works, and
     * the user only ever intends to use the newest one.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationToken t
               SET t.consumedAt = :at
             WHERE t.userId = :userId
               AND t.purpose = :purpose
               AND t.consumedAt IS NULL
            """)
    int consumeOutstanding(
            @Param("userId") UUID userId,
            @Param("purpose") VerificationPurpose purpose,
            @Param("at") Instant at);
}
