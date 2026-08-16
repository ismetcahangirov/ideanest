package az.ideanest.pledge.domain;

import az.ideanest.reward.application.ShippingRate;
import az.ideanest.shared.Money;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * What a pledge costs, broken into the five amounts a {@code pledges} row carries.
 *
 * <p>§4.5's PL-06 in one place: reward plus add-ons plus bonus plus shipping plus
 * tax. The five come back separately rather than as a total because §7.2 stores
 * them separately — {@code base_amount}, {@code addons_amount},
 * {@code bonus_amount}, {@code shipping_amount}, {@code tax_amount}, with
 * {@code total_amount} generated from them — and a total the API could produce but
 * not break down is one a backer cannot be shown and a support agent cannot
 * explain.
 *
 * <p><strong>No Spring, no database, no HTTP.</strong> The same arrangement as
 * {@code SubmissionChecklist} and for the same reason: the arithmetic that decides
 * what a card is charged should be assertable as "this selection, this answer",
 * in milliseconds, without a container. Everything it needs arrives as a
 * {@link PledgeSelection} of plain values. Persisting the result, reserving the
 * stock, and turning a refusal into an RFC 9457 problem detail all belong to the
 * checkout that calls this.
 *
 * <h2>Rounding: there is none, and there cannot be</h2>
 *
 * <p>Every amount that enters this passes through {@link Money}, which refuses more
 * than two decimal places instead of rounding them — the rule is stated there, once,
 * and reused here rather than restated. Everything this then does is exact at two
 * decimal places: a price times a whole quantity, and additions. So no result ever
 * needs rounding, and {@code setScale} is never called with a mode that could
 * discard anything. The one place a rate could produce a third decimal place is
 * tax, and {@link TaxPolicy} says why that decision is #78's rather than this
 * class's.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><strong>Fees (§5.2).</strong> The platform fee and the processing fee are
 *       deducted from what a creator is paid, not added to what a backer is
 *       charged. Adding either to this total would charge a backer for the
 *       platform's revenue, and would do it invisibly. They are epic #59's, they
 *       are computed against the amount raised, and they belong to the payout.
 *   <li><strong>Tax.</strong> Present as {@link TaxPolicy} and zero until #78.
 *   <li><strong>Stock, availability, and whether the tier is even on this
 *       campaign.</strong> PL-01's live stock check and PL-13's reservation are
 *       #51's, and they need a row lock and a transaction — which is exactly what
 *       this class is built not to need.
 * </ul>
 */
public record PledgeQuote(
        BigDecimal baseAmount,
        BigDecimal addonsAmount,
        BigDecimal bonusAmount,
        BigDecimal shippingAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency) {

    public PledgeQuote {
        Objects.requireNonNull(baseAmount, "A quote has a base amount");
        Objects.requireNonNull(addonsAmount, "A quote has an add-ons amount");
        Objects.requireNonNull(bonusAmount, "A quote has a bonus amount");
        Objects.requireNonNull(shippingAmount, "A quote has a shipping amount");
        Objects.requireNonNull(taxAmount, "A quote has a tax amount");
        Objects.requireNonNull(totalAmount, "A quote has a total");

        // The currency is validated once, on the total, and the same normalised
        // code is then what every part is checked against. Five separate
        // validations would let "azn" and "AZN" both appear in one quote.
        Money total = Money.of(totalAmount, currency);
        currency = total.currency();
        totalAmount = total.amount();
        baseAmount = part(baseAmount, currency, "base");
        addonsAmount = part(addonsAmount, currency, "add-ons");
        bonusAmount = part(bonusAmount, currency, "bonus");
        shippingAmount = part(shippingAmount, currency, "shipping");
        taxAmount = part(taxAmount, currency, "tax");

        // §7.2 makes total_amount a generated column: the database will add the
        // five up itself. Checking the same sum here means the two definitions
        // cannot disagree — and a quote built by hand, in a test or by a later
        // caller, cannot claim a total its parts do not support.
        BigDecimal sum = baseAmount
                .add(addonsAmount)
                .add(bonusAmount)
                .add(shippingAmount)
                .add(taxAmount);
        if (sum.compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException(
                    "A total of " + totalAmount + " does not match its parts, which come to " + sum);
        }

        // PL-02 permits a pledge with no reward; it does not permit a pledge of
        // nothing. A zero total would reach the payment provider as an
        // authorisation for zero, which some decline and some approve, and either
        // way it is a backer in the count who gave nothing.
        if (totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("A pledge of " + totalAmount + " is not a pledge");
        }
    }

    /**
     * What §4.5's PL-01 to PL-06 come to, with no tax, which is the platform today.
     *
     * <p>See {@link TaxPolicy} for why the zero is spelled out rather than assumed.
     */
    public static PledgeQuote of(PledgeSelection selection) {
        return of(selection, TaxPolicy.NONE);
    }

    /**
     * The same, against a tax policy the caller chooses.
     *
     * @throws IllegalArgumentException when the selection cannot be quoted: a line
     *     in another currency, a shipped line to a destination the creator has not
     *     priced, a contribution below the price of the tier it is for, or a total
     *     of nothing. Every one of these is a refusal rather than a best guess,
     *     because the alternative in each case is charging a card an amount nobody
     *     agreed to
     */
    public static PledgeQuote of(PledgeSelection selection, TaxPolicy taxPolicy) {
        Objects.requireNonNull(selection, "There is nothing to quote");
        Objects.requireNonNull(taxPolicy, "A quote needs a tax policy, even the one that charges nothing");

        String currency = selection.currency();
        String destination = selection.destinationCountry();

        BigDecimal base = baseOf(selection, currency);
        BigDecimal bonus = selection.contributionAmount().subtract(base);
        if (bonus.signum() < 0) {
            // PL-03 is support *above* the tier price, so this can only be a
            // client working from a price that has since changed. Clamping it to
            // zero would quietly charge the backer the higher figure — more than
            // the number they were looking at when they pressed the button.
            throw new IllegalArgumentException("A contribution of " + selection.contributionAmount()
                    + " is below the " + base + " the selected reward costs");
        }

        BigDecimal addons = BigDecimal.ZERO.setScale(Money.SCALE);
        for (QuotedLine addon : selection.addons()) {
            requireSameCurrency(addon, currency);
            addons = addons.add(addon.lineAmount());
        }

        BigDecimal shipping = BigDecimal.ZERO.setScale(Money.SCALE);
        for (QuotedLine line : selection.lines()) {
            shipping = shipping.add(shippingOn(line, destination, currency));
        }

        BigDecimal net = base.add(addons).add(bonus);
        BigDecimal tax = taxOn(taxPolicy, net, shipping, currency, destination);

        return new PledgeQuote(base, addons, bonus, shipping, tax, net.add(shipping).add(tax), currency);
    }

    // ------------------------------------------------------------------
    // The parts
    // ------------------------------------------------------------------

    /**
     * PL-01 and PL-02: the tier's price, or the whole of a support-only pledge.
     *
     * <p>The two cases are one rule and not a special case. A pledge is priced at
     * what the backer selected; when they selected nothing, what they chose to give
     * <em>is</em> the price, and the bonus above it is nothing. Splitting it the
     * other way — a zero base and the whole amount as a bonus — would make every
     * report of "raised through rewards versus raised as support" read a
     * support-only pledge as a bonus on a reward nobody took.
     */
    private static BigDecimal baseOf(PledgeSelection selection, String currency) {
        QuotedLine reward = selection.reward();
        if (reward == null) {
            return selection.contributionAmount();
        }
        requireSameCurrency(reward, currency);
        return reward.lineAmount();
    }

    /**
     * PL-05: the first unit at the rate, and every unit after it at the additional
     * rate.
     *
     * <p>A line that is not shipped contributes nothing and needs no rate — §7.2 is
     * explicit that no rule applies to a {@code DIGITAL} tier, and asking a backer
     * for postage on a download is the visible half of getting this wrong.
     */
    private static BigDecimal shippingOn(QuotedLine line, String destination, String currency) {
        if (!line.shipped()) {
            return BigDecimal.ZERO.setScale(Money.SCALE);
        }
        if (destination == null) {
            throw new IllegalArgumentException("A pledge containing something to post needs a destination");
        }

        ShippingRate rate = line.shippingRate();
        if (rate == null) {
            // "Anywhere the creator has priced", per ShippingType.INTERNATIONAL. A
            // destination with no rule is one nobody costed, and quoting it at zero
            // makes the creator pay the carrier out of their own funding.
            throw new IllegalArgumentException("The creator has not priced shipping to " + destination);
        }
        String priced = rate.countryCode() == null ? null : rate.countryCode().trim().toUpperCase(Locale.ROOT);
        if (!destination.equals(priced)) {
            // The caller resolved a rate for somewhere else. Charging it would be a
            // plausible number for the wrong country, which is the hardest kind of
            // wrong to notice on an invoice.
            throw new IllegalArgumentException(
                    "A rate for " + rate.countryCode() + " cannot price a pledge going to " + destination);
        }

        BigDecimal first = Money.of(rate.amount(), currency).amount();
        // Null is a flat rate however many are ordered — see ShippingRate, which
        // says an omitted additional amount is deliberate rather than missing.
        BigDecimal additional = rate.additionalItemAmount() == null
                ? BigDecimal.ZERO.setScale(Money.SCALE)
                : Money.of(rate.additionalItemAmount(), currency).amount();
        if (first.signum() < 0 || additional.signum() < 0) {
            throw new IllegalArgumentException("Shipping to " + destination + " cannot cost less than nothing");
        }

        return first.add(additional.multiply(BigDecimal.valueOf(line.quantity() - 1L)));
    }

    /** The policy's answer, held to the same two decimal places as everything else. */
    private static BigDecimal taxOn(
            TaxPolicy policy, BigDecimal net, BigDecimal shipping, String currency, String destination) {

        BigDecimal tax = policy.taxOn(net, shipping, currency, destination);
        if (tax == null) {
            throw new IllegalArgumentException("A tax policy answers with an amount, even when that amount is zero");
        }
        // Money refuses a third decimal place rather than rounding it, which is
        // what makes the rounding decision #78's to take explicitly.
        BigDecimal checked = Money.of(tax, currency).amount();
        if (checked.signum() < 0) {
            throw new IllegalArgumentException("Tax of " + checked + " is a refund, and a quote cannot issue one");
        }
        return checked;
    }

    private static void requireSameCurrency(QuotedLine line, String currency) {
        if (!currency.equals(line.currency())) {
            // Adding the two would produce a number in neither currency, and the
            // pledge has one currency column to record it in. §7.2 gives a tier its
            // campaign's currency precisely so this comparison is possible.
            throw new IllegalArgumentException(
                    "A pledge is in one currency: " + currency + " cannot be added to " + line.currency());
        }
    }

    /** One of the five, normalised the way {@link Money} normalises everything. */
    private static BigDecimal part(BigDecimal amount, String currency, String name) {
        BigDecimal checked = Money.of(amount, currency).amount();
        if (checked.signum() < 0) {
            throw new IllegalArgumentException("A " + name + " amount of " + checked + " is negative");
        }
        return checked;
    }
}
