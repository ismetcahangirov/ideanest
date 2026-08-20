package az.ideanest.pledge.application;

import az.ideanest.shared.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * §4.7's CD-07 and CD-08: what a campaign sold, and where it is going.
 *
 * <h2>Why this is not on the analytics endpoint</h2>
 *
 * <p>{@code GET /analytics} is #95's daily rollup, and the rollup is derived from
 * {@code referral_attributions} — which carries an amount, a currency and a source, and
 * has no reward tier and no destination on it by construction. Adding either would mean
 * either widening that table or having the analytics module read {@code pledges}, and
 * {@code ModuleBoundaryTests} forbids the second for the reason #94 already hit.
 *
 * <p>So the split lives where the facts live: beside the backer report, in the module that
 * owns pledges, and computed at read time. The consequence is stated rather than hidden —
 * <strong>this endpoint is not pre-aggregated</strong> and its cost grows with the number
 * of pledges a campaign has taken, unlike the trend beside it. Two grouped scans of one
 * campaign's rows is the right shape until a campaign is large enough for it not to be,
 * and #95's job is where it would move.
 *
 * <h2>Both splits count backers, not pledges, and the two are the same number</h2>
 *
 * <p>{@code pledges_project_backer_active_key} allows a backer one active pledge per
 * campaign, so a count of reported pledges is a count of people. Named for what is counted
 * anyway, because the day that index is relaxed the two would diverge silently.
 *
 * @param currency what every amount is in, or null when the campaign has no reported
 *     backers at all
 * @param backerCount every reported backer, including those in neither split
 * @param total what they pledged, or null with the currency when there are none
 * @param rewards one entry per tier that has a backer, most valuable first. <strong>These
 *     sum to at most {@link #backerCount()}</strong>: the difference is §4.5's PL-02,
 *     support that took no reward, which is why the total is its own figure and not a sum
 *     of this list
 * @param countries one entry per destination, most valuable first, with pledges that named
 *     none gathered into a single entry whose {@link CountrySlice#country()} is null. Not
 *     dropped: a report whose parts do not add up to its whole is one a creator has to
 *     reconcile by hand
 */
public record BackerBreakdown(
        String currency, long backerCount, Money total, List<RewardSlice> rewards, List<CountrySlice> countries) {

    public BackerBreakdown {
        rewards = List.copyOf(rewards);
        countries = List.copyOf(countries);
    }

    /** A campaign nobody has backed yet. */
    public static BackerBreakdown empty() {
        return new BackerBreakdown(null, 0, null, List.of(), List.of());
    }

    /**
     * One reward tier's share.
     *
     * @param title the tier's title as it stands today. See {@link BackerPage.Backer#rewardTitle()}
     * @param price the tier's own price, for the one comparison this screen exists to
     *     make: {@link #amount()} above {@code price} times {@link #backerCount()} is the
     *     campaign's bonus contributions, and below it is impossible
     */
    public record RewardSlice(UUID rewardTierId, String title, Money price, long backerCount, Money amount) {
    }

    /**
     * One destination's share.
     *
     * @param country ISO 3166-1 alpha-2, or null for the pledges that named no destination
     */
    public record CountrySlice(String country, long backerCount, Money amount) {
    }
}
