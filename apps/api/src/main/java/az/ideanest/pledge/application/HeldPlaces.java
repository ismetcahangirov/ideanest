package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeAddon;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every place a pledge holds, tier by tier, in the order they must be taken. #203.
 *
 * <p><strong>One pledge holds places on several tiers now, and that is the whole
 * reason this exists.</strong> Until #203 a pledge held one place on one tier — §7.2
 * gives it a single {@code reward_tier_id} — so "the place" was a tier identifier and
 * a boolean, and {@code ReservationService} could carry it in two local variables. An
 * add-on is a {@code reward_tiers} row too (V7's {@code is_addon}), with the same
 * three counters and the same {@code reward_tiers_stock_is_within_the_limit} over
 * them, and §4.5's PL-04 lets a backer take several of it. So what a pledge holds is a
 * map from tier to a count, and the four paths that move stock — the draft, the sweep,
 * the confirmation, the edit and the cancellation — each move one of these.
 *
 * <p><strong>Sorted, and that is not tidiness.</strong> Two checkouts taking two
 * add-ons in opposite orders would each hold the row the other wants next: PostgreSQL
 * detects the deadlock and aborts one of them, and the backer who lost gets a 500 for
 * a campaign that had plenty of stock. It is a failure that needs two backers, two
 * add-ons and one moment to reproduce, so it would not have been found here. Taking
 * the rows in one global order — the tier's identifier, which every transaction agrees
 * about without having to coordinate — makes the cycle unconstructible, because no
 * transaction ever waits on a row that comes before one it already holds.
 *
 * <p><strong>What is not decided here.</strong> Which <em>column</em> the places live
 * in — {@code reserved_quantity} for a draft, {@code claimed_quantity} for a confirmed
 * pledge — is a fact about the pledge's state and not about its selection, so it stays
 * with the caller that already read it. A map that carried the column as well would be
 * two facts in one structure, and the second one would be right until somebody built a
 * map from a selection that has no pledge behind it yet, which is exactly what a draft
 * does.
 */
final class HeldPlaces {

    private HeldPlaces() {
        // Static.
    }

    /** Nothing held: a pledge with no reward and no add-ons, which is a real pledge (PL-02). */
    static SortedMap<UUID, Integer> none() {
        return new TreeMap<>();
    }

    /**
     * What a selection would hold, before any of it has been taken.
     *
     * <p>The reward tier counts for one, because §7.2 gives a pledge one
     * {@code reward_tier_id} and two of a tier is either two pledges or an add-on.
     * Each add-on counts for the quantity the backer chose.
     *
     * @param rewardTierId null for §4.5's PL-02, support with no reward
     */
    static SortedMap<UUID, Integer> of(UUID rewardTierId, List<DraftPledge.AddonSelection> addons) {
        SortedMap<UUID, Integer> places = none();
        if (rewardTierId != null) {
            places.put(rewardTierId, 1);
        }
        for (DraftPledge.AddonSelection addon : addons) {
            // Merged rather than put, although DraftPledge.requireDistinctSelections has
            // already refused a selection that names one tier twice. This is what makes
            // the map honest if that rule is ever relaxed: two lines silently becoming
            // one would hold half the places somebody paid for.
            places.merge(addon.rewardTierId(), addon.quantity(), Integer::sum);
        }
        return places;
    }

    /**
     * What a pledge is holding right now, read from the row and its add-on lines.
     *
     * <p>The counterpart of {@link #of} for a pledge that already exists: the same
     * arithmetic over what was stored rather than over what was asked for. An edit
     * compares the two.
     */
    static SortedMap<UUID, Integer> heldBy(Pledge pledge, List<PledgeAddon> addons) {
        SortedMap<UUID, Integer> places = none();
        if (pledge.holdsAPlace()) {
            places.put(pledge.getRewardTierId(), 1);
        }
        for (PledgeAddon addon : addons) {
            places.merge(addon.getRewardTierId(), addon.getQuantity(), Integer::sum);
        }
        return places;
    }

    /**
     * The places {@code after} needs that {@code before} does not already hold.
     *
     * <p>Positive differences only, and a tier that has not moved is absent rather
     * than present with a zero — a zero would reach {@code RewardStock} as a move of
     * no places, which it refuses, and rightly.
     */
    static SortedMap<UUID, Integer> extraIn(SortedMap<UUID, Integer> after, SortedMap<UUID, Integer> before) {
        SortedMap<UUID, Integer> extra = none();
        for (Map.Entry<UUID, Integer> line : after.entrySet()) {
            int difference = line.getValue() - before.getOrDefault(line.getKey(), 0);
            if (difference > 0) {
                extra.put(line.getKey(), difference);
            }
        }
        return extra;
    }
}
