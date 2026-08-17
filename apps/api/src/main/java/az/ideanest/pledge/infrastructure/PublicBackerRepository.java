package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.application.RewardTierBackers;
import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The three questions a campaign page asks about its backers, and no others.
 *
 * <p><strong>Its own interface rather than three more methods on
 * {@link PledgeRepository}.</strong> That one is the checkout's — a pledge by its
 * owner, a backer's live pledge, the sweep's batch — and every query on it is scoped
 * to one person or to one job. These three are scoped to a campaign and answer a
 * stranger. Keeping them apart is the same split {@code PublicRewardCatalogue} makes
 * against {@code RewardService}, and it has the same payoff: nothing here can
 * accidentally be handed a query that was written to be safe only because a backer
 * identifier was part of it.
 *
 * <p><strong>{@link Repository}, not {@link org.springframework.data.jpa.repository.JpaRepository}.</strong>
 * The marker interface publishes exactly the methods declared below.
 * {@code JpaRepository} would additionally publish {@code save}, {@code delete},
 * {@code deleteAll}, and {@code findAll} on a type whose whole purpose is a public
 * read — writes on the public path that nobody intended and a full-table scan a
 * campaign page could reach by autocomplete.
 *
 * <p>Every query takes the counted states as a parameter rather than naming them.
 * Which states are a public backing is a decision, it is made once in
 * {@link az.ideanest.pledge.application.PublicBackers#COUNTED}, and a copy of it
 * inlined in three JPQL strings is three copies to keep in step with a state machine
 * that is still being built.
 */
public interface PublicBackerRepository extends Repository<Pledge, UUID> {

    /**
     * How many people have backed this campaign, publicly counted.
     *
     * <p><strong>Anonymous pledges are counted.</strong> There is no
     * {@code is_anonymous} clause here and there must never be one: PL-12 hides who,
     * not how many, and a count that excluded the people who asked not to be named
     * would understate the campaign to everybody — the visitor deciding whether to
     * join, and the creator reading their own page.
     *
     * <p>One row per pledge is one row per person, because
     * {@code pledges_project_backer_active_key} already refuses a second active pledge
     * from the same backer on the same campaign. A {@code count(distinct p.backerId)}
     * would say the same thing more slowly and would quietly hide it if that index
     * were ever relaxed.
     */
    @Query("SELECT count(p) FROM Pledge p WHERE p.projectId = :projectId AND p.state IN :states")
    long countBackers(@Param("projectId") UUID projectId, @Param("states") Collection<PledgeState> states);

    /**
     * How many backers each reward tier has — §4.4's Rewards tab, which shows a backer
     * count beside every tier.
     *
     * <p>Grouped in the database rather than counted from the list above it, which is
     * the point of it being here at all: a caller that derived per-tier counts by
     * filtering a page of backers would get the page's tally rather than the campaign's,
     * and would get it wrong in exactly the way that looks right on a small campaign.
     *
     * <p>Pledges with no tier are excluded, because they belong to none — §4.5's PL-02,
     * support with no reward. The consequence is stated where it is served: these
     * counts sum to at most {@link #countBackers}, and the difference is the people who
     * backed without taking anything.
     *
     * <p>Ordered by tier identifier so that two reads of unchanged data produce the
     * same body, and therefore the same {@code ETag}. A group-by has no order of its
     * own, and one that reshuffled would break a conditional request for no reason.
     */
    @Query(
            """
            SELECT new az.ideanest.pledge.application.RewardTierBackers(p.rewardTierId, count(p))
              FROM Pledge p
             WHERE p.projectId = :projectId
               AND p.state IN :states
               AND p.rewardTierId IS NOT NULL
             GROUP BY p.rewardTierId
             ORDER BY p.rewardTierId
            """)
    List<RewardTierBackers> countBackersByRewardTier(
            @Param("projectId") UUID projectId, @Param("states") Collection<PledgeState> states);

    /**
     * A page of this campaign's backings, most recently confirmed first.
     *
     * <p>Entities rather than a projection, because what a stranger may be told about
     * each one is {@link az.ideanest.pledge.application.PublicBacker}'s decision and
     * not this query's. A projection here that happened to select {@code backerId}
     * would put the identifier of an anonymous backer into a value object one field
     * away from a serialiser — the arrangement PL-12 exists to make impossible.
     *
     * <p>{@code NULLS LAST} because {@code confirmed_at} is nullable in the schema even
     * though no transition into a counted state leaves it null; a row that somehow had
     * none would otherwise sort to the front of the page, where it would be read as the
     * newest backing on the campaign.
     *
     * <p>The identifier breaks ties. Two pledges confirmed in the same millisecond are
     * ordinary during a launch, and an order that did not decide between them would
     * return them in whichever sequence the plan happened to produce — a body that
     * changes without the data changing, and an {@code ETag} that changes with it.
     */
    @Query(
            """
            SELECT p FROM Pledge p
             WHERE p.projectId = :projectId AND p.state IN :states
             ORDER BY p.confirmedAt DESC NULLS LAST, p.id DESC
            """)
    List<Pledge> findBackings(
            @Param("projectId") UUID projectId, @Param("states") Collection<PledgeState> states, Pageable page);
}
