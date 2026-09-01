package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Project;
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
 * Campaigns, by the things they are actually looked up by.
 *
 * <p>There is deliberately no method that writes {@code state}. Spring Data will
 * generate an update from a derived name — {@code updateStateById} would compile
 * and work — and it would be a second path into the state column that writes no
 * audit row. The only path is {@code ProjectTransitionService} mutating the
 * entity inside a transaction.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Whether this creator already has a campaign under this slug.
     *
     * <p>Scoped to the creator because the unique index is: the public URL is
     * {@code /projects/{creatorSlug}/{projectSlug}}, so two creators may both
     * have a "coffee-table-book".
     */
    boolean existsByCreatorIdAndSlug(UUID creatorId, String slug);

    /**
     * The row, locked until the transaction ends.
     *
     * <p>Used by every state change. Two moderators opening the same submission
     * and clicking approve and reject would otherwise both read {@code SUBMITTED},
     * both find their edge allowed, and both write an audit row — leaving a
     * campaign whose history says it was approved and rejected from the same
     * state, and whose actual state is whichever transaction committed last.
     * With the lock the second one waits, re-reads {@code APPROVED}, and is
     * refused by the transition table.
     *
     * <p>A pessimistic lock rather than a {@code @Version} column: the contested
     * case is rare, the transaction is short, and optimistic locking would
     * surface as a 409 the moderator has to interpret rather than as a wait they
     * never notice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Campaigns whose deadline has passed and which §5.1 has not yet decided — §8.4's
     * {@code campaign-finalizer}, one page at a time.
     *
     * <p><strong>Identifiers, not entities, and no lock.</strong> The finaliser opens a
     * transaction per campaign, so entities loaded here would belong to a transaction
     * that has ended before the first of them is used, and a lock taken here would be
     * held across the whole batch — turning one slow campaign into a queue behind it.
     * {@code findByIdForUpdate} is what claims each row, inside the transaction that
     * decides it.
     *
     * <p><strong>Bounded and oldest first.</strong> A platform whose campaigns all end at
     * midnight must not produce one pass that overlaps its own next tick; the remainder
     * is a minute away, and the campaign that has been waiting longest is closed first.
     *
     * <p>{@code finalized_at IS NULL} is redundant against {@code state = LIVE} — V29's
     * columns are written in the same transaction as the edge out of {@code LIVE}, so no
     * row can be both — and it is here because a redundant predicate that documents an
     * invariant costs nothing on an index the planner already uses for the other two, and
     * because it is the predicate that would stop a partially finalised row from being
     * picked up for ever if that invariant were ever broken.
     *
     * @param now the pass's instant; a campaign whose deadline is exactly now has closed
     * @param page the bound, from {@code ideanest.project.finalisation.batch-size}
     */
    @Query(
            """
            SELECT p.id FROM Project p
            WHERE p.state = az.ideanest.project.domain.ProjectState.LIVE
              AND p.deadline <= :now
              AND p.finalizedAt IS NULL
            ORDER BY p.deadline ASC
            """)
    List<UUID> findClosedCampaigns(@Param("now") Instant now, Pageable page);

    /**
     * Campaigns §5.1 decided in favour of and whose collection has not started — §8.4's
     * {@code charge-processor}, one page at a time.
     *
     * <p>{@code SUCCESSFUL} is by construction the "decided but not collecting" state:
     * {@code CampaignFinalizer} writes it and nothing else does, and the only edge out of
     * it is into {@code COLLECTING}. So this query needs no second predicate and no
     * timestamp column of its own — §6.1's state machine is the flag, which is the
     * arrangement §6.1 says the two states exist for. A campaign that has been through
     * here is not in {@code SUCCESSFUL} any more and cannot be selected twice.
     *
     * <p>Identifiers and no lock, exactly as above: the collection opens a transaction per
     * campaign, and a lock taken here would be held across the batch.
     *
     * <p>Oldest first, by the deadline that produced the state. A backlog after an outage
     * therefore starts with the campaign whose backers have been waiting longest, and a
     * campaign that closed on Tuesday is never held behind one that closed on Thursday.
     */
    @Query(
            """
            SELECT p.id FROM Project p
            WHERE p.state = az.ideanest.project.domain.ProjectState.SUCCESSFUL
            ORDER BY p.deadline ASC
            """)
    List<UUID> findAwaitingCollection(Pageable page);

    /**
     * How many campaigns this creator currently has in the platform's hands.
     *
     * <p><strong>What a subscription's {@code maxActiveCampaigns} is counted against.</strong>
     * The plan says "at most three"; this says which three, and it lives here because these
     * are the project module's rows — {@code PublishingAllowance} has the argument for why
     * the subscription module is not asked to count them.
     *
     * <p><strong>Drafts and pre-launch pages are excluded, and everything terminal is
     * too.</strong> A draft is private and costs the platform nothing, so charging a
     * creator for holding one would be charging them for thinking. A finished campaign is
     * not occupying anything either. What is left is the window in which a campaign is
     * somebody else's work: waiting for a moderator, cleared and waiting to launch, live,
     * or collecting.
     *
     * <p>{@code CHANGES_REQUESTED} counts, which is the one that could go either way. It
     * is a campaign a moderator has already read and will read again, so it is still
     * occupying the queue — and excluding it would let a creator hold any number of
     * campaigns by leaving each one in the state that follows a rejection.
     *
     * <p><strong>The excluded campaign is the one being submitted.</strong> A resubmission
     * from {@code CHANGES_REQUESTED} would otherwise count itself and refuse a creator
     * whose plan permits exactly one campaign — the plan most of them are on.
     */
    @Query(
            """
            SELECT COUNT(p) FROM Project p
            WHERE p.creatorId = :creatorId
              AND p.id <> :excluding
              AND p.state IN (az.ideanest.project.domain.ProjectState.SUBMITTED,
                              az.ideanest.project.domain.ProjectState.CHANGES_REQUESTED,
                              az.ideanest.project.domain.ProjectState.APPROVED,
                              az.ideanest.project.domain.ProjectState.SCHEDULED,
                              az.ideanest.project.domain.ProjectState.LIVE,
                              az.ideanest.project.domain.ProjectState.COLLECTING,
                              az.ideanest.project.domain.ProjectState.LATE_PLEDGE)
            """)
    long countInPlatformHands(@Param("creatorId") UUID creatorId, @Param("excluding") UUID excluding);

    /**
     * The moderation submission queue, oldest first.
     *
     * <p>One row per campaign in the requested state, carrying the transition that put
     * it there — see {@link SubmissionQueueRow} for why that is a {@code LATERAL} join
     * and why the cursor is the transition's identifier rather than the campaign's.
     *
     * <p>Ordered by that identifier ascending, which is oldest first: UUIDv7 sorts by
     * the millisecond it was minted, and the transition was minted when the campaign
     * entered the state. A queue worked newest-first is a queue whose oldest entry is
     * never reached.
     *
     * <p>{@code to_state = p.state} inside the join rather than merely taking the
     * latest transition: a campaign resubmitted after a change request has several
     * transitions into {@code SUBMITTED}, and the one that matters is the last of
     * them. Both spellings answer the same thing while the data is consistent, and
     * this one keeps answering it when a transition is written that does not change
     * the state a campaign is in.
     *
     * <p><strong>{@code LEFT} and {@code COALESCE}, because an invisible campaign is
     * the bug this query exists to fix.</strong> Every state change the application
     * makes writes a transition, so in practice the row is always there — and an inner
     * join would silently drop a campaign that reached {@code SUBMITTED} some other
     * way: a seed script, a manual correction, a migration. That campaign is precisely
     * the one nobody would ever find, which is the failure being repaired rather than a
     * variation on it. It falls back to the campaign's own creation, so it is ordered
     * imperfectly and it is <em>present</em>, and the two are not close in cost.
     */
    @Query(
            value =
                    """
                    SELECT COALESCE(t.id, p.id) AS cursor,
                           COALESCE(t.created_at, p.created_at) AS enteredAt,
                           t.note AS note,
                           p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.goal_amount AS goalAmount,
                           p.currency AS currency, p.creator_id AS creatorId
                    FROM projects p
                    LEFT JOIN LATERAL (
                        SELECT s.id, s.created_at, s.note
                        FROM project_state_transitions s
                        WHERE s.project_id = p.id AND s.to_state = p.state
                        ORDER BY s.created_at DESC, s.id DESC
                        LIMIT 1
                    ) t ON TRUE
                    WHERE p.state = :state
                    ORDER BY COALESCE(t.id, p.id)
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<SubmissionQueueRow> findSubmissionQueue(@Param("state") String state, @Param("limit") int limit);

    /**
     * The page after {@code after}.
     *
     * <p>Spelled out rather than folded into the query above behind a nullable
     * parameter, which is the choice {@code ContentReportRepository} made for the
     * report queue and for the same reason: a {@code :after IS NULL OR …} predicate
     * hands the driver a parameter whose type it has to guess on the first page, and
     * the guess is what fails on a UUID column.
     */
    @Query(
            value =
                    """
                    SELECT COALESCE(t.id, p.id) AS cursor,
                           COALESCE(t.created_at, p.created_at) AS enteredAt,
                           t.note AS note,
                           p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.goal_amount AS goalAmount,
                           p.currency AS currency, p.creator_id AS creatorId
                    FROM projects p
                    LEFT JOIN LATERAL (
                        SELECT s.id, s.created_at, s.note
                        FROM project_state_transitions s
                        WHERE s.project_id = p.id AND s.to_state = p.state
                        ORDER BY s.created_at DESC, s.id DESC
                        LIMIT 1
                    ) t ON TRUE
                    WHERE p.state = :state AND COALESCE(t.id, p.id) > :after
                    ORDER BY COALESCE(t.id, p.id)
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<SubmissionQueueRow> findSubmissionQueueAfter(
            @Param("state") String state, @Param("after") UUID after, @Param("limit") int limit);

    /**
     * The console's campaign directory: everything on the platform, newest first.
     *
     * <p><strong>Not the submission queue with the filter removed.</strong> That query
     * reads the transition that put a campaign in its state, oldest first, because it
     * answers "what has been waiting longest". This answers "what is on the platform",
     * so it is ordered by when the campaign was created and carries what a member of
     * staff looking at a campaign wants to know — how much it has raised and from how
     * many people — which the queue has no use for.
     *
     * <p><strong>Ordered by {@code (created_at, id)} rather than by the key alone.</strong>
     * The primary key is a UUIDv7 and is therefore time-ordered for everything this
     * platform has written, but not for anything seeded or migrated in with a key from
     * somewhere else. Ordering on the column that means what it says costs an index and
     * cannot be wrong.
     */
    @Query(
            value =
                    """
                    SELECT p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.created_at AS createdAt,
                           p.launched_at AS launchedAt, p.deadline AS deadline,
                           p.goal_amount AS goalAmount, p.currency AS currency,
                           p.pledged_amount AS pledgedAmount, p.backers_count AS backersCount,
                           p.creator_id AS creatorId
                    FROM projects p
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<CampaignDirectoryRow> findCampaignDirectory(@Param("limit") int limit);

    /**
     * The page after {@code after}, which names a campaign rather than a position.
     *
     * <p>The row comparison is what makes one key enough for a two-column order: it asks
     * PostgreSQL for everything that sorts after that campaign, in the same terms the
     * {@code ORDER BY} uses. A cursor carrying both halves would be the alternative, and
     * it would put a parseable structure in a URL for no gain — the id is already unique
     * and the subquery is a primary-key lookup.
     *
     * <p>A cursor naming a campaign that has since been deleted answers nothing at all
     * rather than answering the first page again. §17.4 deletes an account and leaves its
     * campaigns behind, so this is a narrow case, and an empty page is the honest one:
     * silently restarting a list somebody is halfway through is how a moderator reads the
     * same twenty campaigns twice.
     */
    @Query(
            value =
                    """
                    SELECT p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.created_at AS createdAt,
                           p.launched_at AS launchedAt, p.deadline AS deadline,
                           p.goal_amount AS goalAmount, p.currency AS currency,
                           p.pledged_amount AS pledgedAmount, p.backers_count AS backersCount,
                           p.creator_id AS creatorId
                    FROM projects p
                    WHERE (p.created_at, p.id) <
                          (SELECT c.created_at, c.id FROM projects c WHERE c.id = :after)
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<CampaignDirectoryRow> findCampaignDirectoryAfter(
            @Param("after") UUID after, @Param("limit") int limit);

    /**
     * The same list, narrowed to one state.
     *
     * <p>Spelled out rather than folded into the query above behind a nullable parameter,
     * which is the choice the submission queue made two methods up and for the same
     * reason: a {@code :state IS NULL OR …} predicate hands the driver a parameter whose
     * type it has to guess.
     */
    @Query(
            value =
                    """
                    SELECT p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.created_at AS createdAt,
                           p.launched_at AS launchedAt, p.deadline AS deadline,
                           p.goal_amount AS goalAmount, p.currency AS currency,
                           p.pledged_amount AS pledgedAmount, p.backers_count AS backersCount,
                           p.creator_id AS creatorId
                    FROM projects p
                    WHERE p.state = :state
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<CampaignDirectoryRow> findCampaignDirectoryInState(
            @Param("state") String state, @Param("limit") int limit);

    /** One state, the page after {@code after}. */
    @Query(
            value =
                    """
                    SELECT p.id AS projectId, p.title AS title, p.slug AS slug,
                           p.state AS state, p.created_at AS createdAt,
                           p.launched_at AS launchedAt, p.deadline AS deadline,
                           p.goal_amount AS goalAmount, p.currency AS currency,
                           p.pledged_amount AS pledgedAmount, p.backers_count AS backersCount,
                           p.creator_id AS creatorId
                    FROM projects p
                    WHERE p.state = :state
                      AND (p.created_at, p.id) <
                          (SELECT c.created_at, c.id FROM projects c WHERE c.id = :after)
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<CampaignDirectoryRow> findCampaignDirectoryInStateAfter(
            @Param("state") String state, @Param("after") UUID after, @Param("limit") int limit);
}
