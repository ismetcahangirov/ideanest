package az.ideanest.pledge.domain;

import az.ideanest.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a backer chose, before anything has been added up.
 *
 * <p>Everything §4.5's PL-01 to PL-05 let a backer decide, and nothing else: a
 * tier or no tier, how much to give, which add-ons and how many, and where it is
 * going. {@link PledgeQuote} turns one of these into the five amounts §7.2's
 * {@code pledges} row carries.
 *
 * <p><strong>A record rather than five parameters</strong>, for the reason
 * {@code CampaignCompleteness} gives: the arguments are not interchangeable, three
 * of them are money or nearly money, and a call with two of them transposed would
 * compile and would charge the wrong card the wrong amount.
 *
 * @param currency the campaign's currency, and the only one in the quote. Every
 *     line is checked against it — see {@link PledgeQuote}
 * @param destinationCountry ISO 3166-1 alpha-2, or null when the backer has not
 *     said yet. Null is legitimate — a digital-only pledge never asks — and it is
 *     refused only if something in the selection actually ships
 * @param reward the tier selected, or <strong>null for PL-02</strong>: a pledge
 *     with no reward at all, which is support and nothing more
 * @param addons PL-04. Each is a {@code reward_tiers} row with {@code is_addon}
 *     true, with the quantity the backer chose. Establishing that the row really
 *     is an add-on, is on this campaign, and is in stock happens where the rows are
 *     loaded; by the time they are here they are prices and counts
 * @param contributionAmount <strong>what the backer chose to give</strong>, which
 *     is the tier price plus PL-03's bonus, or — with no tier — the whole of a
 *     support-only pledge. Add-ons, shipping, and tax are added on top of it and
 *     are not part of it: they are what the selection costs, not what the backer
 *     decided to give
 */
public record PledgeSelection(
        String currency,
        String destinationCountry,
        QuotedLine reward,
        List<QuotedLine> addons,
        BigDecimal contributionAmount) {

    /** ISO 3166-1 alpha-2, the same shape {@code shipping_rules.country_code} holds. */
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    public PledgeSelection {
        Objects.requireNonNull(contributionAmount, "A pledge is an amount somebody chose to give");

        // Through Money, so the currency and the two-decimal-place rule are
        // checked once, in the place that already states them.
        Money contribution = Money.of(contributionAmount, currency);
        contributionAmount = contribution.amount();
        currency = contribution.currency();

        if (contributionAmount.signum() < 0) {
            throw new IllegalArgumentException("A backer cannot give less than nothing");
        }

        if (destinationCountry != null) {
            destinationCountry = destinationCountry.trim().toUpperCase(Locale.ROOT);
            if (!COUNTRY.matcher(destinationCountry).matches()) {
                throw new IllegalArgumentException("A destination is a two-letter ISO 3166-1 country code");
            }
        }

        // Absent and empty are the same selection, and making the caller pass
        // List.of() would put a null check at every call site instead of here.
        addons = addons == null ? List.of() : List.copyOf(addons);

        if (reward != null && reward.quantity() != 1) {
            // §7.2 gives a pledge one reward_tier_id. Two of a tier is two
            // pledges, or it is an add-on, and both of those already have a
            // shape; a quantity here would be an amount with nowhere to be
            // written down.
            throw new IllegalArgumentException("A pledge selects one reward tier, not " + reward.quantity());
        }
    }

    /** PL-02: support only, with nothing promised in return. */
    public boolean isSupportOnly() {
        return reward == null;
    }

    /** Every line the backer selected, the reward first, for whatever has to walk all of them. */
    public List<QuotedLine> lines() {
        if (reward == null) {
            return addons;
        }
        return Stream.concat(Stream.of(reward), addons.stream()).toList();
    }
}
