package az.ideanest.fee.infrastructure;

import az.ideanest.fee.domain.FeeSchedule;
import az.ideanest.fee.domain.FeeScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V49's schedules, by the two questions asked of them — #311.
 *
 * <p><strong>Pricing asks {@link #inForceAt}</strong>, once per scope, and it asks for a
 * historical instant rather than for "now" — that is the whole reason the table has a
 * window. A payout recalculated next year has to reach the same schedule it reached when
 * it was approved.
 *
 * <p><strong>The screen asks {@link #history}</strong>, which returns every window
 * including the closed ones. Unpaged: a scope accumulates one row per rate change, and a
 * platform that changed its fees often enough for this to need a cursor has a larger
 * problem than a long screen.
 */
public interface FeeScheduleRepository extends JpaRepository<FeeSchedule, UUID> {

    /**
     * The schedule that priced this scope at that instant, if there was one.
     *
     * <p>Two queries rather than one with a nullable parameter, following
     * {@code ContentReportRepository}: {@code (:ref IS NULL OR s.scopeRef = :ref)} would
     * make the platform-wide lookup depend on the driver's willingness to infer the type
     * of a null UUID, and would read as though the two cases were the same question.
     *
     * <p>{@code effectiveTo IS NULL OR effectiveTo > :at} is the half-open window
     * {@code FeeSchedule.coversInstant} states in Java. Both exist because this one is
     * what the index serves and that one is what a caller holding an entity can ask.
     */
    @Query(
            """
            SELECT s FROM FeeSchedule s
            WHERE s.scope = :scope
              AND s.scopeRef = :scopeRef
              AND s.effectiveFrom <= :at
              AND (s.effectiveTo IS NULL OR s.effectiveTo > :at)
            """)
    Optional<FeeSchedule> inForceAt(
            @Param("scope") FeeScope scope, @Param("scopeRef") UUID scopeRef, @Param("at") Instant at);

    /** The same question for the platform-wide schedule, whose reference is null. */
    @Query(
            """
            SELECT s FROM FeeSchedule s
            WHERE s.scope = az.ideanest.fee.domain.FeeScope.PLATFORM
              AND s.scopeRef IS NULL
              AND s.effectiveFrom <= :at
              AND (s.effectiveTo IS NULL OR s.effectiveTo > :at)
            """)
    Optional<FeeSchedule> platformInForceAt(@Param("at") Instant at);

    /**
     * The open schedule for a scope, for the close-then-open pair.
     *
     * <p>{@code FOR UPDATE} is deliberately absent: V49's partial unique index is what
     * makes two concurrent replacements safe, and it does it without a lock — the loser
     * gets a constraint violation, which {@code FeeSchedules.replace} turns into a
     * refusal the console can retry. A lock here would serialise a screen nobody uses
     * twice a month.
     */
    @Query(
            """
            SELECT s FROM FeeSchedule s
            WHERE s.scope = :scope
              AND (:scopeRef IS NULL AND s.scopeRef IS NULL OR s.scopeRef = :scopeRef)
              AND s.effectiveTo IS NULL
            """)
    Optional<FeeSchedule> openFor(@Param("scope") FeeScope scope, @Param("scopeRef") UUID scopeRef);

    /** Every window ever written, newest first. See the class comment on why it is unpaged. */
    @Query("SELECT s FROM FeeSchedule s ORDER BY s.effectiveFrom DESC, s.scope ASC")
    List<FeeSchedule> history();
}
