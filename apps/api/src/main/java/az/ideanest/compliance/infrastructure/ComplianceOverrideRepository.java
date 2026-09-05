package az.ideanest.compliance.infrastructure;

import az.ideanest.compliance.domain.ComplianceOverride;
import az.ideanest.compliance.domain.ComplianceRequirement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V66's overrides, by the two questions asked of them — #436.
 *
 * <p><strong>A gate asks {@link #liveFor}</strong>, which is the hot one: once per verification,
 * per submission, per payout. It takes an instant rather than reading the clock, so that a
 * decision re-derived later reaches the same answer it reached at the time — {@code
 * FeeScheduleRepository} makes the identical argument about a schedule, and for the identical
 * reason.
 *
 * <p><strong>The account screen asks {@link #forSubject}</strong>, which returns every row:
 * expired, revoked and live alike. That is the point of it. An override that stopped working
 * is still a thing that was granted, and the next person to look at the account has to see
 * that somebody was let past a rule in March even though the exception ended in April.
 * Unpaged, because an account that has accumulated enough overrides to need a cursor is
 * itself the finding.
 */
public interface ComplianceOverrideRepository extends JpaRepository<ComplianceOverride, UUID> {

    /**
     * The live override of this requirement for this account, if there is one.
     *
     * <p>{@code ORDER BY expiresAt DESC} and the first row, rather than an aggregate: V66
     * deliberately allows two live overrides of the same requirement — a second granted before
     * the first expired, for a second reason — and a gate wants the one that lasts longest.
     * The alternative was a unique index, which would have turned the second grant into a 500
     * on a screen where two readable rows are the honest outcome.
     *
     * <p>{@code revokedAt IS NULL AND grantedAt <= :at AND expiresAt > :at} is the half-open
     * window {@code ComplianceOverride.isLiveAt} states in Java. Both exist because this one
     * is what the partial index serves and that one is what a caller holding an entity can
     * ask.
     */
    @Query(
            """
            SELECT o FROM ComplianceOverride o
            WHERE o.subjectUserId = :subjectUserId
              AND o.requirement = :requirement
              AND o.revokedAt IS NULL
              AND o.grantedAt <= :at
              AND o.expiresAt > :at
            ORDER BY o.expiresAt DESC
            LIMIT 1
            """)
    Optional<ComplianceOverride> liveFor(
            @Param("subjectUserId") UUID subjectUserId,
            @Param("requirement") ComplianceRequirement requirement,
            @Param("at") Instant at);

    /** Everything ever granted to this account, newest first. The account screen's read. */
    @Query(
            """
            SELECT o FROM ComplianceOverride o
            WHERE o.subjectUserId = :subjectUserId
            ORDER BY o.grantedAt DESC
            """)
    List<ComplianceOverride> forSubject(@Param("subjectUserId") UUID subjectUserId);
}
