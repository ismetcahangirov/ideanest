package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.pledge.domain.PledgeQuote;
import az.ideanest.pledge.domain.PledgeSelection;
import az.ideanest.pledge.domain.QuotedLine;
import az.ideanest.pledge.domain.TaxPolicy;
import az.ideanest.reward.application.ShippingRate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.5's PL-02 to PL-06, asserted as exact decimals.
 *
 * <p>{@code CLAUDE.md} puts money arithmetic in the set of things that are not
 * optional to test, and this is the arithmetic that decides what a card is
 * charged. Every assertion here is against a {@link BigDecimal} with its scale, not
 * against a {@code double} and not against a comparison that ignores scale: a total
 * that is right to within a hundredth is a total that is wrong.
 *
 * <p><strong>A plain unit test.</strong> No Spring, no container, no HTTP — which is
 * the reason the quote was written as a pure type over plain values. The whole of
 * PL-06 can be stated as "this selection, this answer".
 */
class PledgeQuoteTests {

    private static final String AZN = "AZN";

    // ------------------------------------------------------------------
    // PL-01 to PL-04: what the backer chose to give
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PL-01: a reward and nothing else costs the tier's price")
    void aRewardOnlyPledgeCostsTheTierPrice() {
        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN, null, QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1), List.of(), new BigDecimal("80.00")));

        assertThat(quote.baseAmount()).isEqualTo(new BigDecimal("80.00"));
        assertThat(quote.addonsAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.bonusAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.shippingAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.taxAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("80.00"));
        assertThat(quote.currency()).isEqualTo(AZN);
    }

    @Test
    @DisplayName("PL-02: a pledge with no reward is support, and the whole of it is the base")
    void aSupportOnlyPledgeIsAllBase() {
        PledgeSelection selection = new PledgeSelection(AZN, null, null, List.of(), new BigDecimal("25.00"));
        PledgeQuote quote = PledgeQuote.of(selection);

        assertThat(selection.isSupportOnly()).isTrue();
        // Not a zero base with a 25.00 bonus: a bonus is the amount above a tier
        // price, and there is no tier. Reported the other way, every "raised as
        // support" figure would count this as a bonus on a reward nobody took.
        assertThat(quote.baseAmount()).isEqualTo(new BigDecimal("25.00"));
        assertThat(quote.bonusAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("PL-03: what a backer gives above the tier price is the bonus")
    void bonusSupportIsTheAmountAboveTheTierPrice() {
        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                null,
                QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1),
                List.of(),
                new BigDecimal("120.50")));

        assertThat(quote.baseAmount()).isEqualTo(new BigDecimal("80.00"));
        assertThat(quote.bonusAmount()).isEqualTo(new BigDecimal("40.50"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("120.50"));
    }

    @Test
    @DisplayName("PL-03: a contribution below the tier price is refused, not clamped")
    void aContributionBelowTheTierPriceIsRefused() {
        PledgeSelection stale = new PledgeSelection(
                AZN, null, QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1), List.of(), new BigDecimal("75.00"));

        // Clamping the bonus to zero would charge 80.00 to somebody who was looking
        // at 75.00 when they pressed the button. The client is working from a price
        // that has moved, and that is worth an error rather than a larger charge.
        assertThatThrownBy(() -> PledgeQuote.of(stale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the 80.00");
    }

    @Test
    @DisplayName("PL-04: each add-on is its price times its quantity, summed")
    void addonsAreQuantityTimesPrice() {
        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                null,
                QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1),
                List.of(
                        QuotedLine.notShipped(new BigDecimal("12.50"), AZN, 3), // 37.50
                        QuotedLine.notShipped(new BigDecimal("4.99"), AZN, 2), // 9.98
                        QuotedLine.notShipped(new BigDecimal("7.25"), AZN, 1)), // 7.25
                new BigDecimal("80.00")));

        assertThat(quote.addonsAmount()).isEqualTo(new BigDecimal("54.73"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("134.73"));
    }

    // ------------------------------------------------------------------
    // PL-05: shipping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PL-05: the first unit at the rate, every unit after it at the additional rate")
    void shippingChargesTheFirstUnitAndThenTheRest() {
        ShippingRate poster = ShippingRate.flat("AZ", new BigDecimal("5.00"), new BigDecimal("1.50"));

        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                "AZ",
                QuotedLine.shipped(new BigDecimal("80.00"), AZN, 1, poster),
                List.of(QuotedLine.shipped(new BigDecimal("10.00"), AZN, 4, poster)),
                new BigDecimal("80.00")));

        // 5.00 for the reward's single unit, then 5.00 + three times 1.50 for the
        // four add-ons.
        assertThat(quote.shippingAmount()).isEqualTo(new BigDecimal("14.50"));
        assertThat(quote.addonsAmount()).isEqualTo(new BigDecimal("40.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("134.50"));
    }

    @Test
    @DisplayName("PL-05: an omitted additional-item amount is a flat rate, not a missing one")
    void anAbsentAdditionalRateIsFlat() {
        ShippingRate flat = ShippingRate.flat("AZ", new BigDecimal("9.00"), null);

        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                "AZ",
                QuotedLine.shipped(new BigDecimal("30.00"), AZN, 1, flat),
                List.of(),
                new BigDecimal("30.00")));

        assertThat(quote.shippingAmount()).isEqualTo(new BigDecimal("9.00"));
    }

    @Test
    @DisplayName("PL-05: a tier that is not shipped is not charged shipping")
    void aDigitalTierIsNotShipped() {
        // ShippingType.DIGITAL, NONE and LOCAL_PICKUP all arrive here as
        // shipped=false. §7.2 says no rule applies to them, and asking a backer for
        // postage on a download is the visible half of getting this wrong.
        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                "AZ",
                QuotedLine.notShipped(new BigDecimal("15.00"), AZN, 1),
                List.of(QuotedLine.notShipped(new BigDecimal("5.00"), AZN, 2)),
                new BigDecimal("15.00")));

        assertThat(quote.shippingAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("PL-05: a destination the creator has not priced is refused, not charged zero")
    void anUnpricedDestinationIsRefused() {
        PledgeSelection unpriced = new PledgeSelection(
                AZN,
                "TR",
                QuotedLine.shipped(new BigDecimal("80.00"), AZN, 1, null),
                List.of(),
                new BigDecimal("80.00"));

        // "Anywhere the creator has priced", per ShippingType.INTERNATIONAL.
        // Quoting an uncosted destination at zero makes the creator pay the
        // carrier out of their own funding.
        assertThatThrownBy(() -> PledgeQuote.of(unpriced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has not priced shipping to TR");
    }

    @Test
    @DisplayName("PL-05: a rate resolved for another country cannot price this pledge")
    void aRateForTheWrongCountryIsRefused() {
        ShippingRate elsewhere = ShippingRate.flat("AZ", new BigDecimal("5.00"), new BigDecimal("1.50"));
        PledgeSelection wrong = new PledgeSelection(
                AZN,
                "GE",
                QuotedLine.shipped(new BigDecimal("80.00"), AZN, 1, elsewhere),
                List.of(),
                new BigDecimal("80.00"));

        // A plausible number for the wrong country is the hardest kind of wrong to
        // notice on an invoice.
        assertThatThrownBy(() -> PledgeQuote.of(wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot price a pledge going to GE");
    }

    @Test
    @DisplayName("PL-05: something to post needs somewhere to post it to")
    void aShippedLineWithoutADestinationIsRefused() {
        ShippingRate rate = ShippingRate.flat("AZ", new BigDecimal("5.00"), BigDecimal.ZERO);
        PledgeSelection nowhere = new PledgeSelection(
                AZN,
                null,
                QuotedLine.shipped(new BigDecimal("80.00"), AZN, 1, rate),
                List.of(),
                new BigDecimal("80.00"));

        assertThatThrownBy(() -> PledgeQuote.of(nowhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a destination");
    }

    // ------------------------------------------------------------------
    // Tax: #78's seam, and zero until then
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tax is zero, because there is no tax model until #78")
    void taxIsZeroUntilItIsImplemented() {
        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                "AZ",
                QuotedLine.shipped(
                        new BigDecimal("80.00"), AZN, 1, ShippingRate.flat("AZ", new BigDecimal("5.00"), null)),
                List.of(),
                new BigDecimal("100.00")));

        // This assertion is the point of TaxPolicy.NONE existing: it fails the day
        // something starts charging tax without #78 being closed, which is what
        // separates a documented zero from a rate nobody decided.
        assertThat(quote.taxAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("105.00"));
    }

    @Test
    @DisplayName("a tax policy is given the net and the shipping separately")
    void aTaxPolicySeesNetAndShippingApart() {
        ShippingRate rate = ShippingRate.flat("AZ", new BigDecimal("5.00"), null);
        PledgeSelection selection = new PledgeSelection(
                AZN,
                "AZ",
                QuotedLine.shipped(new BigDecimal("80.00"), AZN, 1, rate),
                List.of(QuotedLine.notShipped(new BigDecimal("10.00"), AZN, 2)),
                new BigDecimal("90.00"));

        // Jurisdictions disagree about whether carriage is taxable, so #78 has to be
        // able to tell the two apart. Here: 80.00 base + 20.00 add-ons + 10.00 bonus
        // as the net, and 5.00 of shipping beside it.
        TaxPolicy netOnly = (net, shipping, currency, destination) -> {
            assertThat(net).isEqualTo(new BigDecimal("110.00"));
            assertThat(shipping).isEqualTo(new BigDecimal("5.00"));
            assertThat(currency).isEqualTo(AZN);
            assertThat(destination).isEqualTo("AZ");
            return new BigDecimal("11.00");
        };

        PledgeQuote quote = PledgeQuote.of(selection, netOnly);

        assertThat(quote.taxAmount()).isEqualTo(new BigDecimal("11.00"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("126.00"));
    }

    @Test
    @DisplayName("a tax amount with a third decimal place is refused rather than rounded")
    void anUnrepresentableTaxIsRefused() {
        PledgeSelection selection = new PledgeSelection(
                AZN, null, QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1), List.of(), new BigDecimal("80.00"));
        TaxPolicy unrounded = (net, shipping, currency, destination) -> new BigDecimal("14.4056");

        // Which way a rate is rounded is a matter for the jurisdiction. Guessing it
        // here would put an arbitrary rounding rule under every invoice, so the
        // decision is pushed back to whoever implements #78.
        assertThatThrownBy(() -> PledgeQuote.of(selection, unrounded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }

    // ------------------------------------------------------------------
    // The refusals that protect the total
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pledge is in one currency")
    void aLineInAnotherCurrencyIsRefused() {
        PledgeSelection mixed = new PledgeSelection(
                AZN,
                null,
                QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 1),
                List.of(QuotedLine.notShipped(new BigDecimal("10.00"), "USD", 1)),
                new BigDecimal("80.00"));

        // Adding the two would produce a number in neither currency, and §7.2 gives
        // the pledge one currency column to record the answer in.
        assertThatThrownBy(() -> PledgeQuote.of(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AZN cannot be added to USD");
    }

    @Test
    @DisplayName("a pledge of nothing is refused")
    void aZeroTotalIsRefused() {
        PledgeSelection nothing = new PledgeSelection(AZN, null, null, List.of(), BigDecimal.ZERO);

        // PL-02 permits a pledge with no reward; it does not permit a pledge of
        // nothing. Zero reaches the provider as an authorisation for zero, which
        // some decline and some approve, and either way it is a backer in the count
        // who gave nothing.
        assertThatThrownBy(() -> PledgeQuote.of(nothing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a pledge");
    }

    @Test
    @DisplayName("a quote whose parts do not add up to its total is refused")
    void aQuoteCannotClaimATotalItsPartsDoNotSupport() {
        // §7.2 makes total_amount a generated column, so the database adds the five
        // up itself. This is the same sum, checked here, so the two cannot disagree.
        assertThatThrownBy(() -> new PledgeQuote(
                        new BigDecimal("80.00"),
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("80.00"),
                        AZN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match its parts");
    }

    @Test
    @DisplayName("a pledge selects one reward tier, not several")
    void aRewardLineCarriesOneUnit() {
        QuotedLine two = QuotedLine.notShipped(new BigDecimal("80.00"), AZN, 2);

        // §7.2 gives a pledge a single reward_tier_id. Two of a tier is two pledges
        // or it is an add-on, and both of those already have a shape.
        assertThatThrownBy(() -> new PledgeSelection(AZN, null, two, List.of(), new BigDecimal("160.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one reward tier");
    }

    // ------------------------------------------------------------------
    // Why none of this is a double
    // ------------------------------------------------------------------

    @Test
    @DisplayName("amounts a double cannot add come out exact")
    void theArithmeticIsExactWhereADoubleIsNot() {
        // The canonical case, in the currency's own units. CLAUDE.md names this
        // exact sum as the reason money is never a floating-point number here, and
        // on a funding platform the value being approximated is somebody's pledge.
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);

        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                null,
                QuotedLine.notShipped(new BigDecimal("0.10"), AZN, 1),
                List.of(QuotedLine.notShipped(new BigDecimal("0.70"), AZN, 1)),
                new BigDecimal("0.30")));

        // The base and the bonus are the two halves of that sum: 0.10 and 0.20,
        // which have to come to exactly 0.30 before anything else is added.
        assertThat(quote.baseAmount()).isEqualTo(new BigDecimal("0.10"));
        assertThat(quote.bonusAmount()).isEqualTo(new BigDecimal("0.20"));
        assertThat(quote.baseAmount().add(quote.bonusAmount())).isEqualTo(new BigDecimal("0.30"));
        assertThat(quote.addonsAmount()).isEqualTo(new BigDecimal("0.70"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    @DisplayName("a repeated fraction stays exact over many add-ons")
    void manySmallAmountsStayExact() {
        // Ten of 0.07 is 0.70 exactly. As doubles it is 0.7000000000000001
        // multiplied and 0.7000000000000002 added up, and a total assembled out of
        // those is one that cannot be reconciled against what the card was charged.
        assertThat(0.07 * 10).isNotEqualTo(0.7);

        PledgeQuote quote = PledgeQuote.of(new PledgeSelection(
                AZN,
                null,
                null,
                List.of(QuotedLine.notShipped(new BigDecimal("0.07"), AZN, 10)),
                new BigDecimal("9.30")));

        assertThat(quote.addonsAmount()).isEqualTo(new BigDecimal("0.70"));
        assertThat(quote.totalAmount()).isEqualTo(new BigDecimal("10.00"));
    }
}
