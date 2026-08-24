package az.ideanest.moderation.infrastructure;

import az.ideanest.moderation.domain.ContentReport;
import az.ideanest.moderation.domain.ReportState;
import az.ideanest.moderation.domain.ReportTargetType;
import az.ideanest.moderation.domain.TargetReportCount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reports, by the questions actually asked of them.
 *
 * <p><strong>Neither write is a {@code save}.</strong> Both of the two statements
 * that change this table name their own condition, and both do it because the
 * alternative loses a race that happens in normal use:
 *
 * <ul>
 *   <li>{@link #insertIfAbsent} is an {@code ON CONFLICT DO NOTHING} against V23's
 *       partial unique index. A read-then-write check in Java loses the race between
 *       two taps on a slow connection — both see no open report, both insert, and
 *       the campaign now carries two complaints from one person, which is exactly
 *       the number a moderator triages by.
 *   <li>{@link #resolveIfOpen} is a conditional update. Two moderators working one
 *       queue would both read {@code OPEN}, both decide, and the second decision
 *       would overwrite the first with nothing to say there had been one.
 * </ul>
 *
 * <p>Both return a row count, and in both cases the caller uses it to tell "I did
 * this" from "somebody already had" — which are different answers to the client and
 * the same answer to the database.
 *
 * <p><strong>The queue is two methods rather than one with a nullable cursor.</strong>
 * {@code (:after IS NULL OR r.id > :after)} would be one query and would make the
 * first page depend on the driver's willingness to infer the type of a null UUID
 * parameter. Two named queries say which page is being asked for at the call site,
 * where a reader can see it.
 */
public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

    /**
     * Records a report, or does nothing because this reporter already has an open
     * one about this target.
     *
     * <p>Native because JPQL has no {@code ON CONFLICT}, and the conflict is the
     * point: the database decides whether this call created the row, and both
     * outcomes are success as far as the reporter is concerned.
     *
     * <p>{@code DO NOTHING} rather than {@code DO UPDATE}. A repeat report must not
     * write to the existing row at all — changing its reason, or its date, would let
     * somebody re-order the queue by reporting the same thing again, and the reason
     * on file would stop being the one the moderator was first shown.
     *
     * <p>The enum values arrive as strings because the columns are {@code text} with
     * a {@code CHECK}, not a PostgreSQL enum type; V23's header has why.
     *
     * @return 1 when this call created the row, 0 when an open report was already
     *     there. Both are success; the caller uses it only to decide which row to
     *     read back
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO content_reports (id, target_type, target_id, reporter_id, reason, detail)
                    VALUES (:id, :targetType, :targetId, :reporterId, :reason, :detail)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("reporterId") UUID reporterId,
            @Param("reason") String reason,
            @Param("detail") String detail);

    /** This reporter's open report about this target, if they have one. */
    @Query(
            """
            SELECT r FROM ContentReport r
            WHERE r.targetType = :targetType
              AND r.targetId = :targetId
              AND r.reporterId = :reporterId
              AND r.state = az.ideanest.moderation.domain.ReportState.OPEN
            """)
    Optional<ContentReport> findOpenReport(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetId") UUID targetId,
            @Param("reporterId") UUID reporterId);

    /**
     * Decides a report, if nobody has decided it already.
     *
     * <p>The three columns of a resolution are written by one statement because V23
     * refuses a row that has only some of them — a decision with no decider, or a
     * note on a report nobody signed off. Keeping them together here means the
     * constraint is a check on the migration rather than something the application
     * has to remember.
     *
     * <p><strong>{@code now()} rather than an instant the application supplies</strong>,
     * which is V21's argument about {@code audit_logs.occurred_at} and the opposite of
     * what V17 and V18 insist on. The distinction is whether the value drives a rule:
     * a reservation's expiry does and needs a {@code Clock} a test can move, and this
     * one records a fact about the row. Taking it from the database also removes the
     * only way {@code content_reports_resolution_follows_the_report} could ever fire —
     * a container clock a few milliseconds behind the application's, which is a flake
     * that reproduces once a fortnight on somebody else's machine.
     *
     * @return 1 when this call is the one that decided it, 0 when somebody else did
     */
    // The persistence context is cleared afterwards because the row this statement
    // changed is frequently loaded as an entity in the same transaction; without it
    // the caller's next read would come from the first-level cache and would still
    // describe an open report.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE content_reports
                       SET state = :state, resolved_by = :moderatorId,
                           resolved_at = now(), resolution_note = :note
                     WHERE id = :id AND state = 'OPEN'
                    """,
            nativeQuery = true)
    int resolveIfOpen(
            @Param("id") UUID id,
            @Param("state") String state,
            @Param("moderatorId") UUID moderatorId,
            @Param("note") String note);

    /**
     * The first page of the queue: one state, oldest first.
     *
     * <p>Ordered by identifier, which is a UUID v7 and therefore in arrival order
     * (§7.3) — so this is "the reports that have waited longest" without a second
     * column in the index.
     */
    @Query("SELECT r FROM ContentReport r WHERE r.state = :state ORDER BY r.id")
    List<ContentReport> firstPage(@Param("state") ReportState state, Pageable limit);

    /**
     * The next page, from the last identifier of the previous one.
     *
     * <p><strong>Keyset paging rather than an offset.</strong> A moderator working a
     * queue changes it as they go — every resolution removes a row from
     * {@code OPEN} — and an offset against a shifting set skips rows, which on this
     * table means a complaint nobody ever reads.
     */
    @Query("SELECT r FROM ContentReport r WHERE r.state = :state AND r.id > :after ORDER BY r.id")
    List<ContentReport> pageAfter(
            @Param("state") ReportState state, @Param("after") UUID after, Pageable limit);

    /**
     * The first page of one kind of target's queue.
     *
     * <p><strong>Why the filter is a second pair of methods and not a parameter on the
     * first.</strong> {@code (:targetType IS NULL OR r.targetType = :targetType)} would be
     * one query and would make every unfiltered page depend on the driver inferring the
     * type of a null enum parameter. The class comment above makes the same argument about
     * the cursor, and it is the same argument: the call site says which question it is
     * asking, where a reader can see it.
     *
     * <p>AD-09's profile queue is the caller that needs this, and it needs it because the
     * alternative — reading the whole queue and dropping three quarters of it in the
     * browser — turns a keyset cursor into a lie. A page of twenty-five reports holding two
     * profile reports is not a page of two, and the client has no way to ask for the rest.
     */
    @Query("SELECT r FROM ContentReport r WHERE r.state = :state AND r.targetType = :targetType ORDER BY r.id")
    List<ContentReport> firstPageOfType(
            @Param("state") ReportState state, @Param("targetType") ReportTargetType targetType, Pageable limit);

    /** The next page of one kind of target's queue. See {@link #pageAfter} on keyset paging. */
    @Query(
            """
            SELECT r FROM ContentReport r
            WHERE r.state = :state AND r.targetType = :targetType AND r.id > :after
            ORDER BY r.id
            """)
    List<ContentReport> pageAfterOfType(
            @Param("state") ReportState state,
            @Param("targetType") ReportTargetType targetType,
            @Param("after") UUID after,
            Pageable limit);

    /**
     * How many people have an open complaint about each of these targets.
     *
     * <p>One query for the whole page rather than one per row. It is the only triage
     * signal the queue has — "this campaign has been reported by fourteen people" is
     * a different fact from "this campaign has been reported" — and computing it per
     * row would make the cost of reading the queue grow with the size of the page,
     * on the screen that has to stay usable when there is a lot in it.
     *
     * <p>Callers pass a non-empty list; an empty {@code IN} is not valid SQL, and
     * the caller already knows the page was empty.
     */
    @Query(
            """
            SELECT new az.ideanest.moderation.domain.TargetReportCount(r.targetType, r.targetId, count(r))
            FROM ContentReport r
            WHERE r.state = az.ideanest.moderation.domain.ReportState.OPEN
              AND r.targetId IN :targetIds
            GROUP BY r.targetType, r.targetId
            """)
    List<TargetReportCount> countOpenByTarget(@Param("targetIds") List<UUID> targetIds);
}
