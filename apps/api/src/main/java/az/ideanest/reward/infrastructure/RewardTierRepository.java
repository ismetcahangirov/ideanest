package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.RewardTier;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reward tiers, by the campaign they belong to.
 *
 * <p>There is deliberately no <em>derived</em> method that writes
 * {@code claimed_quantity} or {@code reserved_quantity}. Spring Data would generate
 * one from a name, and it would be a path into the stock columns that takes no lock
 * and checks no limit. The three written by hand below — two of #51's and one of
 * #52's — are each a single conditional statement that cannot be called wrongly: the
 * condition that keeps the tier from overselling is inside the {@code UPDATE}, not
 * in the caller.
 */
public interface RewardTierRepository extends JpaRepository<RewardTier, UUID> {

    /**
     * The reward list of one campaign, in display order.
     *
     * <p>Ordered by {@code createdAt} within a {@code sortOrder} so that two tiers
     * that were never reordered — both at zero — come back in a stable order
     * rather than in whichever order the planner produced. A list that changes
     * order between two reads of the same data looks like a bug in the editor.
     */
    List<RewardTier> findByProjectIdOrderBySortOrderAscCreatedAtAsc(UUID projectId);

    long countByProjectId(UUID projectId);

    /**
     * The highest position in use, so a new tier can be appended.
     *
     * <p>Empty for a campaign with no tiers yet. Appending rather than inserting at
     * the top: a creator who adds a tier while looking at their list expects it
     * where they can see it was added, and reordering is a separate, explicit act.
     */
    @Query("SELECT max(tier.sortOrder) FROM RewardTier tier WHERE tier.projectId = :projectId")
    Optional<Integer> findHighestSortOrder(@Param("projectId") UUID projectId);

    /**
     * Takes one place on a tier, if the tier has one left. #51.
     *
     * <p><strong>This one statement is the reservation mechanism.</strong> The
     * {@code UPDATE} takes PostgreSQL's row lock on the tier, and under READ
     * COMMITTED it re-reads the row behind that lock and re-evaluates its
     * {@code WHERE} clause against what the transaction that held the lock actually
     * committed. So two checkouts racing for the last place are serialised by the
     * database: the second one's condition is false by the time it is evaluated, no
     * row is updated, and it is told the tier is full. A read followed by a write
     * cannot do that — both callers would read "one place left", and the code that
     * checked would not be wrong, merely not serialised.
     *
     * <p>{@code claimed_quantity} is in the condition as well as
     * {@code reserved_quantity}, because a place that has been confirmed is as taken
     * as one being held. It is the same expression as V7's
     * {@code reward_tiers_stock_is_within_the_limit}, which is the second line: if
     * this condition were ever wrong the constraint refuses the transaction rather
     * than letting the tier oversell.
     *
     * <p>A null {@code limit_quantity} is unlimited and always matches. The count is
     * still incremented, because §5.3 lets a creator add a limit later and the floor
     * it may be lowered to is the places already taken.
     *
     * <p><strong>{@code version} is deliberately not incremented.</strong> The column
     * is optimistic locking for the creator's fields, and the pledge module cannot
     * touch those — {@code claimed_quantity} and {@code reserved_quantity} are mapped
     * non-insertable and non-updatable, so there is no update here for an editor to
     * lose. Bumping it would make every checkout invalidate whatever edit the creator
     * had open, which on a busy campaign is an editor that cannot save. The rule it
     * might be thought to protect — that a limit is never lowered below what is taken
     * — is held by the check constraint against the row as it really is, not against
     * the copy the editor happens to be holding.
     *
     * <p>Native rather than JPQL, and no {@code clearAutomatically}: nothing reads a
     * {@code RewardTier} entity after this in the same transaction, and clearing the
     * persistence context here would detach whatever the caller was in the middle of
     * saving. The flush before it is kept, so a pending write cannot be ordered after
     * this statement.
     *
     * @return 1 when a place was taken, 0 when the tier is full or no longer exists
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET reserved_quantity = reserved_quantity + 1
                     WHERE id = :id
                       AND (limit_quantity IS NULL OR claimed_quantity + reserved_quantity < limit_quantity)
                    """,
            nativeQuery = true)
    int reserveOnePlace(@Param("id") UUID id);

    /**
     * Gives one held place back, when a reservation lapses or a draft is abandoned.
     *
     * <p>{@code reserved_quantity > 0} is a guard against a release running twice,
     * not against the constraint. {@code reward_tiers_reserved_is_not_negative} would
     * refuse a count below zero, but the damage of a double release happens above
     * zero, where it is silent: the tier would offer a place that no reservation ever
     * gave back, and the first person to take it would be the one who found out at
     * fulfilment.
     *
     * @return 1 when a place was given back, 0 when there was none to give
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET reserved_quantity = reserved_quantity - 1
                     WHERE id = :id AND reserved_quantity > 0
                    """,
            nativeQuery = true)
    int releaseOnePlace(@Param("id") UUID id);

    /**
     * Turns a held place into a claimed one, when a draft is confirmed. #52.
     *
     * <p><strong>One statement, and the reason is that the tier must never be
     * momentarily short.</strong> The obvious alternative is the two statements that
     * already exist here — release one, then claim one — and both orderings are
     * wrong, differently.
     *
     * <ul>
     *   <li><strong>Release first.</strong> Between the two statements
     *       {@code claimed + reserved} is one below the limit, and that is not a
     *       private detail: it is exactly the expression {@link #reserveOnePlace}
     *       evaluates. A checkout arriving in that window takes the last place of a
     *       tier that has already sold it, and the second person to find out is the
     *       one who does not get a reward. The window is short, which makes it a bug
     *       that reproduces under load and never in a test.
     *   <li><strong>Claim first.</strong> Now {@code claimed + reserved} is one
     *       <em>above</em> the limit for the same instant, and V7's
     *       {@code reward_tiers_stock_is_within_the_limit} is a check constraint:
     *       PostgreSQL evaluates it at the end of the statement, so it refuses the
     *       first update outright. A perfectly legitimate confirmation of the last
     *       place would fail on a full tier — always, not occasionally.
     * </ul>
     *
     * <p>So the correct order is no order. Both columns move in one {@code UPDATE},
     * the sum does not change, no other transaction sees an intermediate state
     * because there is not one, and the constraint is evaluated against a row whose
     * total is exactly what it was before.
     *
     * <p>{@code reserved_quantity > 0} is the same guard {@link #releaseOnePlace}
     * carries and it is doing the same job: a confirmation that ran twice would move
     * a second place out of a reservation that no longer exists, inventing a claim
     * against nothing. Here it also serves as the honest answer to "was this pledge
     * really holding a place" — a zero return is the caller's signal that it was
     * not.
     *
     * <p>{@code version} is deliberately not incremented, for the reason
     * {@link #reserveOnePlace} gives: the column is optimistic locking for the
     * creator's own fields, and bumping it on every confirmation would invalidate
     * whatever edit the creator had open.
     *
     * @return 1 when a held place became a claimed one, 0 when there was no held
     *     place to convert
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET reserved_quantity = reserved_quantity - 1,
                           claimed_quantity = claimed_quantity + 1
                     WHERE id = :id AND reserved_quantity > 0
                    """,
            nativeQuery = true)
    int commitOnePlace(@Param("id") UUID id);

    /**
     * Takes one place directly as a claimed one. #56.
     *
     * <p><strong>For a pledge that is already {@code CONFIRMED} and changes its
     * reward.</strong> The backer is committed, so the place they take on the new
     * tier is committed too: routing it through {@link #reserveOnePlace} and then
     * {@link #commitOnePlace} would be two statements to express one fact, and in
     * between them the tier would hold a reservation belonging to a pledge that is
     * not a draft — which is precisely the row §8.4's sweep looks for and precisely
     * the state {@code ReservationExpiry} refuses to reason about.
     *
     * <p>The condition is {@link #reserveOnePlace}'s, unchanged and deliberately so:
     * a place is a place whichever column ends up counting it, and V7's
     * {@code reward_tiers_stock_is_within_the_limit} bounds the same sum. A tier with
     * no limit is unlimited and always matches, and the count still moves, for
     * {@link #reserveOnePlace}'s reason.
     *
     * <p>{@code version} is not incremented, for {@link #reserveOnePlace}'s reason.
     *
     * @return 1 when a place was claimed, 0 when the tier is full or no longer exists
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET claimed_quantity = claimed_quantity + 1
                     WHERE id = :id
                       AND (limit_quantity IS NULL OR claimed_quantity + reserved_quantity < limit_quantity)
                    """,
            nativeQuery = true)
    int claimOnePlace(@Param("id") UUID id);

    /**
     * Gives one claimed place back, when a confirmed pledge is cancelled or changes
     * its reward. #56.
     *
     * <p><strong>The counterpart of {@link #releaseOnePlace}, and a different
     * statement because it is a different claim.</strong> A {@code DRAFT} holds a
     * place in {@code reserved_quantity} and a {@code CONFIRMED} pledge holds one in
     * {@code claimed_quantity}; releasing the wrong column would leave the tier
     * counting a place nobody holds and short of one somebody does, and the total
     * would still look right. §9.7's "backer changes their mind while live" is a
     * cancellation of a confirmed pledge, so this is the column that moves.
     *
     * <p>{@code claimed_quantity > 0} is {@link #releaseOnePlace}'s guard doing
     * {@link #releaseOnePlace}'s job: the damage of a release that ran twice happens
     * above zero, where the constraint cannot see it and the tier quietly offers a
     * place that no pledge ever gave back.
     *
     * @return 1 when a place was given back, 0 when there was none to give
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET claimed_quantity = claimed_quantity - 1
                     WHERE id = :id AND claimed_quantity > 0
                    """,
            nativeQuery = true)
    int releaseOneClaimedPlace(@Param("id") UUID id);

    /**
     * The named tiers, but only the ones that really belong to this campaign.
     *
     * <p>The campaign is part of the query rather than a filter afterwards, for
     * {@code RewardStock#priceOf}'s reason: a pledge naming another campaign's tier
     * is a row V17's composite foreign key refuses, and answering the question this
     * way is what lets the checkout say which selection was wrong instead of handing
     * back a constraint violation.
     *
     * <p>In display order, so that a quote built from this reads in the order the
     * backer saw.
     */
    @Query(
            """
            SELECT tier FROM RewardTier tier
            WHERE tier.projectId = :projectId AND tier.id IN :ids
            ORDER BY tier.sortOrder, tier.createdAt
            """)
    List<RewardTier> findSelected(@Param("projectId") UUID projectId, @Param("ids") Collection<UUID> ids);

    /**
     * What a backer could take instead of a tier that is full. §10.4's
     * {@code meta.availableAlternatives}.
     *
     * <p>Four exclusions, and each of them is the difference between an alternative
     * and a dead link. Add-ons are bought alongside a reward rather than instead of
     * one. A secret tier is reachable only through its own link, and listing its
     * identifier in an error body would be the disclosure the secret exists to
     * prevent. A tier outside its availability window cannot be selected today. And
     * a tier with no places left is the problem, not the answer to it.
     *
     * <p>The stock condition is the same expression {@link #reserveOnePlace}
     * evaluates and the same one V7's constraint bounds, written once more here
     * because this is a read: the answer may be stale by the time the backer acts on
     * it, and the reservation statement — not this — is what decides whether they
     * get a place.
     */
    @Query(
            """
            SELECT tier.id FROM RewardTier tier
            WHERE tier.projectId = :projectId
              AND tier.id <> :excluding
              AND tier.addon = false
              AND tier.secret = false
              AND (tier.availableFrom IS NULL OR tier.availableFrom <= :at)
              AND (tier.availableUntil IS NULL OR tier.availableUntil > :at)
              AND (tier.limitQuantity IS NULL
                   OR tier.claimedQuantity + tier.reservedQuantity < tier.limitQuantity)
            ORDER BY tier.sortOrder, tier.createdAt
            """)
    List<UUID> findAvailableAlternatives(
            @Param("projectId") UUID projectId, @Param("excluding") UUID excluding, @Param("at") Instant at);
}
