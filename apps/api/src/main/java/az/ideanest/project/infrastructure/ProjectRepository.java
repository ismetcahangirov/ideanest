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
}
