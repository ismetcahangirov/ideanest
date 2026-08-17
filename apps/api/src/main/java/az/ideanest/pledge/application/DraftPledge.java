package az.ideanest.pledge.application;

import az.ideanest.shared.money.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * What a backer asked for at the checkout, before anything has been priced or
 * reserved.
 *
 * <p>The application-layer form of {@code POST /v1/pledges/draft}'s body, with the
 * caller established and the header validated. It is not
 * {@code pledge.domain.PledgeSelection}: that one carries resolved prices and
 * shipping rates, and getting from this to that is a read of the reward module.
 *
 * @param rewardTierId null for §4.5's PL-02, support with no reward
 * @param addons PL-04. Each is a {@code reward_tiers} row with {@code is_addon}
 *     true, with the quantity the backer chose
 * @param contribution <strong>what the backer chose to give</strong>: the tier's
 *     price plus PL-03's bonus, or — with no tier — the whole of a support-only
 *     pledge. Add-ons, shipping, and tax are added on top and are not part of it
 * @param shippingCountry ISO 3166-1 alpha-2, or null when nothing in the selection
 *     is posted
 * @param anonymous PL-12. Accepted and stored here because the contract carries it;
 *     what it actually hides — the public backer list, the campaign page, the
 *     creator's exports — is #57's, and none of it exists yet
 * @param idempotencyKey §10.3's header, already established to be a UUID. Recorded
 *     on the pledge as well as in {@code idempotency_keys}; see
 *     {@code pledge.domain.NewPledge}
 */
public record DraftPledge(
        UUID projectId,
        UUID backerId,
        UUID rewardTierId,
        List<AddonSelection> addons,
        Money contribution,
        String shippingCountry,
        boolean anonymous,
        String referrerCode,
        String idempotencyKey) {

    public DraftPledge {
        Objects.requireNonNull(projectId, "A pledge backs a campaign");
        Objects.requireNonNull(backerId, "A pledge is made by somebody");
        Objects.requireNonNull(contribution, "A pledge is an amount somebody chose to give");
        // Absent and empty are the same selection, and making the caller pass
        // List.of() would put a null check at every call site instead of here.
        addons = addons == null ? List.of() : List.copyOf(addons);

        requireDistinctSelections(rewardTierId, addons);
    }

    /**
     * The two ways a selection can name one tier twice, refused once for every path
     * that builds one.
     *
     * <p><strong>A static method rather than two copies of the rule.</strong> #56's
     * {@code PATCH} resolves a partial body against a stored pledge, so the selection
     * it ends up with was never a {@code DraftPledge} and never passed through the
     * constructor above — an edit that added, as an add-on, the tier the pledge
     * already has as its reward would otherwise reach the database and be refused by
     * a constraint instead of by an answer. One rule, one place, both callers.
     *
     * @throws IllegalArgumentException when an add-on is listed twice, or when the
     *     reward tier is also listed as an add-on
     */
    public static void requireDistinctSelections(UUID rewardTierId, List<AddonSelection> addons) {
        // One line per tier. Two lines for one add-on is a quantity expressed twice,
        // and `pledge_addons`' primary key refuses the second row — so without this
        // the client would get a constraint violation instead of being told what was
        // wrong with what it sent. Merging them instead would be the platform
        // deciding how many of something somebody meant to buy.
        Set<UUID> seen = new HashSet<>();
        for (AddonSelection addon : addons) {
            if (!seen.add(addon.rewardTierId())) {
                throw new IllegalArgumentException(
                        "Add-on " + addon.rewardTierId() + " is selected twice; use one line with a quantity");
            }
        }
        if (rewardTierId != null && seen.contains(rewardTierId)) {
            // The reward and an add-on are the same row in `reward_tiers` (V7), so
            // this is representable. It is still nonsense: the tier would be priced
            // once as the reward and again as an extra, and §7.2 gives a pledge one
            // reward_tier_id to record the first of those in.
            throw new IllegalArgumentException("Reward tier " + rewardTierId + " is selected as an add-on as well");
        }
    }

    /** Every tier this pledge names, the reward first, for the one read that resolves them all. */
    public List<UUID> selectedTierIds() {
        Stream<UUID> selected = addons.stream().map(AddonSelection::rewardTierId);
        if (rewardTierId == null) {
            return selected.toList();
        }
        return Stream.concat(Stream.of(rewardTierId), selected).toList();
    }

    /**
     * One add-on and how many of it.
     *
     * @param quantity at least one. A line for none of something is a line the client
     *     should have left out, and the database refuses the row
     */
    public record AddonSelection(UUID rewardTierId, int quantity) {

        public AddonSelection {
            Objects.requireNonNull(rewardTierId, "An add-on line names a tier");
            if (quantity < 1) {
                throw new IllegalArgumentException("An add-on is selected at least once, not " + quantity);
            }
        }
    }
}
