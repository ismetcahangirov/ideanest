package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rounding rule, which exists in exactly one place.
 *
 * <p>#133 is "rounding rules stated once and enforced". These tests are what makes
 * "once" true rather than intended: they assert the mode and the scale at their
 * single source, so a second rounding decision taken anywhere else has to disagree
 * with a test that names the first.
 */
class MoneyRoundingTests {

    @Test
    @DisplayName("the mode is HALF_EVEN, and it is stated here")
    void theModeIsBankersRounding() {
        // HALF_EVEN rather than HALF_UP because the values being rounded are
        // computed ones -- a 5% platform fee, a processing fee, a split of a
        // payout -- and HALF_UP is biased upwards on every halfway case. Over a
        // campaign's worth of fees that bias always favours the same party.
        assertThat(MoneyRounding.MODE).isEqualTo(RoundingMode.HALF_EVEN);
    }

    @Test
    @DisplayName("the scale is the currency's minor unit")
    void theScaleComesFromTheCurrency() {
        assertThat(MoneyRounding.scaleOf("AZN")).isEqualTo(2);
        assertThat(MoneyRounding.scaleOf("USD")).isEqualTo(2);
        // A currency with no minor unit at all. 100.5 yen is not an amount of
        // money, and a scale of 2 would let one be constructed.
        assertThat(MoneyRounding.scaleOf("JPY")).isZero();
    }

    @Test
    @DisplayName("an ISO-shaped code the JVM does not know falls back to the column scale")
    void anUnknownCodeUsesTheColumnScale() {
        // The database checks the shape of a currency code, not its membership of
        // the JVM's table, so a well-formed code has to be usable. Two decimal
        // places is what the column holds.
        assertThat(MoneyRounding.scaleOf("QQQ")).isEqualTo(MoneyRounding.COLUMN_SCALE);
    }

    @Test
    @DisplayName("a currency with more minor units than the column holds is refused")
    void aThreeDecimalCurrencyIsRefused() {
        // numeric(14,2) cannot hold 1.234 dinars: PostgreSQL would round it on
        // the way in, silently, which is the one thing this whole type exists to
        // prevent. Refused loudly until §7.2 says otherwise.
        assertThatThrownBy(() -> MoneyRounding.scaleOf("KWD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KWD");
    }

    @Test
    @DisplayName("a halfway value rounds to the even neighbour, both ways")
    void halfwayValuesRoundToEven() {
        // The pair that separates HALF_EVEN from HALF_UP: HALF_UP would answer
        // 0.02 and 0.03, always away from zero.
        assertThat(MoneyRounding.round(new BigDecimal("0.015"), "AZN")).isEqualTo(new BigDecimal("0.02"));
        assertThat(MoneyRounding.round(new BigDecimal("0.025"), "AZN")).isEqualTo(new BigDecimal("0.02"));

        // Symmetric below zero. A refund is rounded the same way the charge was.
        assertThat(MoneyRounding.round(new BigDecimal("-0.015"), "AZN")).isEqualTo(new BigDecimal("-0.02"));
        assertThat(MoneyRounding.round(new BigDecimal("-0.025"), "AZN")).isEqualTo(new BigDecimal("-0.02"));
    }

    @Test
    @DisplayName("rounding respects the currency's own scale")
    void roundingUsesTheCurrencyScale() {
        assertThat(MoneyRounding.round(new BigDecimal("100.5"), "JPY")).isEqualTo(new BigDecimal("100"));
        assertThat(MoneyRounding.round(new BigDecimal("101.5"), "JPY")).isEqualTo(new BigDecimal("102"));
    }

    @Test
    @DisplayName("an exact amount is padded; an inexact one is refused rather than rounded")
    void exactRefusesWhatItWouldHaveToDiscard() {
        assertThat(MoneyRounding.exact(new BigDecimal("5000"), "AZN")).isEqualTo(new BigDecimal("5000.00"));
        assertThatCode(() -> MoneyRounding.exact(new BigDecimal("5000.500"), "AZN")).doesNotThrowAnyException();

        // The difference between the two entry points is the whole design: input
        // that arrived from outside is refused when it does not fit, and only a
        // value this code computed is rounded.
        assertThatThrownBy(() -> MoneyRounding.exact(new BigDecimal("5000.555"), "AZN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }
}
