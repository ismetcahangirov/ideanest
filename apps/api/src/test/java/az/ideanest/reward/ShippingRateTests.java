package az.ideanest.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.pledge.domain.PledgeQuote;
import az.ideanest.pledge.domain.PledgeSelection;
import az.ideanest.reward.application.ShippingRate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.8's PM-11 and PM-12 (#77): what a parcel costs to post.
 *
 * <p><strong>Money arithmetic, which {@code CLAUDE.md} says is not optional to
 * test.</strong> This is the one calculation on the platform that cannot come out
 * exact — grams divided by a thousand, times a rate — so the rounding rule is stated
 * once in {@link ShippingRate#costFor} and pinned here. Everything else about a quote
 * is exact by construction and {@code PledgeQuoteTests} covers it.
 *
 * <p>A plain unit test with no container: the whole point of keeping the arithmetic in
 * a record is that "this rate, this parcel, this answer" is assertable in milliseconds.
 */
class ShippingRateTests {

    private static final String AZN = "AZN";

    private static ShippingRate rate(String amount, String additional, String perKilogram) {
        return new ShippingRate(
                "DE", new BigDecimal(amount), new BigDecimal(additional), new BigDecimal(perKilogram));
    }

    // ------------------------------------------------------------------
    // Flat rates, which is every rate the platform quoted before #77
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one unit costs the flat amount")
    void oneUnitCostsTheFlatAmount() {
        assertThat(rate("12.00", "3.00", "0.00").costFor(1, 0, AZN)).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("each unit after the first costs the additional amount")
    void additionalUnitsCostTheAdditionalAmount() {
        assertThat(rate("12.00", "3.00", "0.00").costFor(4, 0, AZN)).isEqualByComparingTo("21.00");
    }

    @Test
    @DisplayName("a zero additional amount is a flat rate however many are ordered")
    void aZeroAdditionalAmountIsAFlatRate() {
        assertThat(rate("12.00", "0.00", "0.00").costFor(9, 0, AZN)).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("free shipping is zero and is a legitimate rate")
    void freeShippingIsZero() {
        assertThat(rate("0.00", "0.00", "0.00").costFor(3, 5000, AZN)).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // Weight — PM-12, and the rounding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the weight rate is added to the flat amount rather than replacing it")
    void weightIsAddedToTheFlatAmount() {
        // A handling charge of 5.00 plus 4.00 per kilogram, on a two-kilogram parcel.
        assertThat(rate("5.00", "0.00", "4.00").costFor(1, 2000, AZN)).isEqualByComparingTo("13.00");
    }

    @Test
    @DisplayName("the whole consignment is weighed, not one unit of it")
    void everyUnitContributesItsWeight() {
        // Three units of 500g is 1.5kg, at 4.00 per kilogram, plus the 5.00 handling
        // and two additional-item charges of 1.00.
        assertThat(rate("5.00", "1.00", "4.00").costFor(3, 500, AZN)).isEqualByComparingTo("13.00");
    }

    /**
     * The rounding rule, stated once and pinned here.
     *
     * <p>750 grams at 4.50 per kilogram is 3.375, which no arrangement of the inputs
     * makes exact. Half-up rather than up, because rounding every parcel up would
     * overcharge every backer by up to a qəpik in the platform's favour — which is not
     * the platform's money to take.
     */
    @Test
    @DisplayName("a weight component that does not divide exactly is rounded half-up, once")
    void theWeightComponentIsRoundedHalfUp() {
        assertThat(rate("0.00", "0.00", "4.50").costFor(1, 750, AZN))
                .as("3.375 rounds to 3.38")
                .isEqualByComparingTo("3.38");

        assertThat(rate("0.00", "0.00", "4.50").costFor(1, 250, AZN))
                .as("1.125 rounds to 1.13")
                .isEqualByComparingTo("1.13");

        assertThat(rate("0.00", "0.00", "1.00").costFor(1, 1114, AZN))
                .as("1.114 rounds down, which is the half of half-up nobody remembers to check")
                .isEqualByComparingTo("1.11");
    }

    @Test
    @DisplayName("the rounding happens once, on the weight alone, so the flat amounts stay exact")
    void roundingDoesNotTouchTheFlatAmounts() {
        // 5.01 + 2.00 + (0.333... rounded) — the flat parts must survive untouched.
        assertThat(rate("5.01", "2.00", "1.00").costFor(2, 500, AZN)).isEqualByComparingTo("8.01");
    }

    @Test
    @DisplayName("a tier whose items have no recorded weight is charged only the flat amount")
    void anUnweighedTierIsNotAnError() {
        // V7 makes items.weight_grams optional and most campaigns never fill it in.
        // Refusing to quote would turn an incomplete catalogue into a checkout nobody
        // can complete.
        assertThat(rate("12.00", "0.00", "4.00").costFor(1, 0, AZN)).isEqualByComparingTo("12.00");
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a negative rate is refused rather than treated as a discount")
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> rate("-1.00", "0.00", "0.00").costFor(1, 0, AZN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than nothing");
    }

    @Test
    @DisplayName("a quantity below one is a bug in the caller, not a free parcel")
    void aQuantityBelowOneIsRefused() {
        assertThatThrownBy(() -> rate("12.00", "0.00", "0.00").costFor(0, 0, AZN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a third decimal place in a rate is refused rather than rounded")
    void aThirdDecimalPlaceIsRefused() {
        // Money's rule, reused. PostgreSQL would round it silently, and a shipping
        // line that quietly gains a qəpik is a pledge total that does not add up.
        assertThatThrownBy(() -> rate("12.001", "0.00", "0.00").costFor(1, 0, AZN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Through a whole quote, which is where the number reaches a card
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a weight-priced line reaches the pledge total as the rate says it should")
    void aWeightPricedLineReachesTheTotal() {
        var reward = az.ideanest.pledge.domain.QuotedLine.shipped(
                new BigDecimal("40.00"), AZN, 1, rate("5.00", "0.00", "4.00"), 1500);

        PledgeQuote quote = PledgeQuote.of(
                new PledgeSelection(AZN, "DE", reward, List.of(), new BigDecimal("40.00")));

        assertThat(quote.shippingAmount()).as("5.00 handling plus 1.5kg at 4.00").isEqualByComparingTo("11.00");
        assertThat(quote.totalAmount()).isEqualByComparingTo("51.00");
    }

    @Test
    @DisplayName("a rate resolved for the wrong country cannot price a pledge")
    void aRateForElsewhereIsRefused() {
        var reward = az.ideanest.pledge.domain.QuotedLine.shipped(
                new BigDecimal("40.00"), AZN, 1, rate("5.00", "0.00", "0.00"), 0);

        assertThatThrownBy(() -> PledgeQuote.of(
                        new PledgeSelection(AZN, "FR", reward, List.of(), new BigDecimal("40.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot price a pledge going to FR");
    }
}
