package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerBreakdown;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * §4.7's CD-07 and CD-08 on the wire: what a campaign sold, and where it is going.
 *
 * <p>The two arrays a chart is drawn from, and the totals they are shares of. Both are
 * ordered most valuable first by the query, so a client renders them in the order it
 * receives them and two reads of unchanged data produce the same body.
 *
 * <p><strong>Neither array is a share.</strong> No percentage is sent: a share is
 * {@code amount ÷ total} and the total is in the same body, so computing it on the server
 * would mean rounding it there and being asked, eventually, why the slices do not add to a
 * hundred. The client rounds for display, once, where it knows how many decimals fit.
 *
 * @param currency what every amount is in, absent on a campaign with no backers yet
 * @param countries one entry per destination, with the pledges that named none under an
 *     absent {@code country}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackerBreakdownResponse(
        String currency, long backerCount, Money total, List<RewardSlice> rewards, List<CountrySlice> countries) {

    public static BackerBreakdownResponse of(BackerBreakdown breakdown) {
        return new BackerBreakdownResponse(
                breakdown.currency(),
                breakdown.backerCount(),
                breakdown.total(),
                breakdown.rewards().stream().map(RewardSlice::of).toList(),
                breakdown.countries().stream().map(CountrySlice::of).toList());
    }

    /**
     * One reward tier's share.
     *
     * @param price the tier's own price, so a client can show what a tier asks beside what
     *     it took. Absent when the tier has since been removed and only the pledges
     *     naming it remain
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RewardSlice(UUID rewardTierId, String title, Money price, long backerCount, Money amount) {

        static RewardSlice of(BackerBreakdown.RewardSlice slice) {
            return new RewardSlice(
                    slice.rewardTierId(), slice.title(), slice.price(), slice.backerCount(), slice.amount());
        }
    }

    /** One destination's share. {@code country} absent means "named no destination". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CountrySlice(String country, long backerCount, Money amount) {

        static CountrySlice of(BackerBreakdown.CountrySlice slice) {
            return new CountrySlice(slice.country(), slice.backerCount(), slice.amount());
        }
    }
}
