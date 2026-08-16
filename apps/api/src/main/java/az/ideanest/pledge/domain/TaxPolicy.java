package az.ideanest.pledge.domain;

import java.math.BigDecimal;

/**
 * How much tax a pledge attracts.
 *
 * <p><strong>There is no tax model on this platform, and this is where its absence
 * is said out loud.</strong> {@code pledges.tax_amount} is a column §7.2 already
 * defines and §4.5's PL-06 already counts into the total, so the quote has to
 * produce a number for it. The only honest number today is zero, and
 * {@link #NONE} is that zero — named, documented, and pinned to the issue that
 * replaces it, in the way {@code DiscoveryCapability} pins the discovery options
 * nothing serves yet.
 *
 * <p><strong>Tax collection is #78</strong>, in a different epic. Inventing a rate
 * here would be worse than leaving it at zero in every direction that matters: a
 * hard-coded 18% is a legal position nobody took, applied to backers in countries
 * nobody checked, and it would be indistinguishable in the ledger from a rate that
 * had been decided. A zero that is written down and tested is a gap anybody can
 * see; a plausible-looking rate is a gap nobody looks for.
 *
 * <p>An interface rather than a constant, because the shape of the seam is the
 * part worth getting right now. When #78 lands it supplies an implementation — a
 * jurisdiction lookup, a rate table, a provider — and nothing in
 * {@link PledgeQuote} changes.
 *
 * <h2>The rounding this deliberately does not do</h2>
 *
 * <p>A rate applied to an amount produces more decimal places than money has, and
 * which way that is rounded is a matter for the jurisdiction, not for arithmetic:
 * some require rounding per line, some per invoice, some to the nearest unit.
 * {@link PledgeQuote} refuses an answer with more than two decimal places rather
 * than rounding one — so an implementation has to make that decision explicitly,
 * where somebody can read it, which is the whole point of #78 existing.
 */
@FunctionalInterface
public interface TaxPolicy {

    /**
     * The tax on one pledge, in the campaign's currency, at two decimal places.
     *
     * @param netAmount the reward, the add-ons, and the bonus together — what the
     *     backer is giving before delivery
     * @param shippingAmount kept separate from {@code netAmount} because
     *     jurisdictions disagree about whether carriage is taxable, and an
     *     implementation given only the sum could not tell them apart
     * @param currency the campaign's currency, which is the only one in the quote
     * @param destinationCountry ISO 3166-1 alpha-2, or null when nothing ships and
     *     the backer was never asked. Where a pledge is taxed is #78's question and
     *     the destination is not necessarily the answer to it, which is another
     *     reason not to guess at one here
     * @return the tax, never negative and never more than two decimal places
     */
    BigDecimal taxOn(BigDecimal netAmount, BigDecimal shippingAmount, String currency, String destinationCountry);

    /**
     * No tax, on anything, anywhere. <strong>What the platform does today.</strong>
     *
     * <p>Replaced by #78, and until then this is the only implementation. The tests
     * that pin it to zero are the tests that will fail the day something starts
     * charging tax without that issue being closed.
     */
    TaxPolicy NONE = (netAmount, shippingAmount, currency, destinationCountry) -> BigDecimal.ZERO;
}
