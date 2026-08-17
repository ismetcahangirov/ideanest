package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic, which is the part that fails silently.
 *
 * <p>{@code CLAUDE.md} §3 puts money arithmetic in the set of things that are not
 * optional to test, and gives the reason: nothing reports a rounding error. It
 * appears later, in a payout that does not reconcile, and by then the pledge that
 * caused it is one of ten thousand.
 */
class MoneyArithmeticTests {

    private static Money azn(String amount) {
        return Money.of(new BigDecimal(amount), "AZN");
    }

    @Test
    @DisplayName("addition and subtraction are exact")
    void additionIsExact() {
        // The reason this type exists at all: 0.1 + 0.2 is not 0.3 in a double,
        // and on this platform the difference is somebody's pledge.
        assertThat(azn("0.10").plus(azn("0.20"))).isEqualTo(azn("0.30"));
        assertThat(azn("19.99").plus(azn("0.01"))).isEqualTo(azn("20.00"));
        assertThat(azn("20.00").minus(azn("19.99"))).isEqualTo(azn("0.01"));
    }

    @Test
    @DisplayName("subtraction may go below zero, and says so")
    void subtractionMayGoNegative() {
        // A refund and a reversal are negative amounts. Clamping at zero here
        // would make a ledger that cannot express the entry it has to store.
        Money owed = azn("10.00").minus(azn("12.50"));

        assertThat(owed).isEqualTo(azn("-2.50"));
        assertThat(owed.isNegative()).isTrue();
        assertThat(owed.negated()).isEqualTo(azn("2.50"));
        assertThat(owed.abs()).isEqualTo(azn("2.50"));
    }

    @Test
    @DisplayName("multiplying by a whole number cannot need rounding")
    void multiplyingByAQuantityIsExact() {
        assertThat(azn("19.99").times(3)).isEqualTo(azn("59.97"));
        assertThat(azn("19.99").times(0)).isEqualTo(Money.zero("AZN"));
        assertThat(azn("19.99").times(-1)).isEqualTo(azn("-19.99"));
    }

    @Test
    @DisplayName("multiplying by a fraction rounds once, HALF_EVEN")
    void multiplyingByAFactorRoundsHalfEven() {
        // 0.015 and 0.025 exactly. HALF_UP would answer 0.02 and 0.03; the rule
        // in MoneyRounding answers 0.02 for both.
        assertThat(azn("0.10").times(new BigDecimal("0.15"))).isEqualTo(azn("0.02"));
        assertThat(azn("0.10").times(new BigDecimal("0.25"))).isEqualTo(azn("0.02"));

        // Exact products are not rounded at all, so nothing is lost when nothing
        // has to be.
        assertThat(azn("10.00").times(new BigDecimal("0.125"))).isEqualTo(azn("1.25"));
    }

    @Test
    @DisplayName("a percentage is the fee calculation, and it rounds the same way")
    void aPercentageRoundsHalfEven() {
        // §5.2: the platform fee is 5% of the amount raised.
        assertThat(azn("1000.00").percentage(new BigDecimal("5"))).isEqualTo(azn("50.00"));
        assertThat(azn("1234.57").percentage(new BigDecimal("5"))).isEqualTo(azn("61.73"));

        // 5% of 12.50 is 0.625 exactly -- the halfway case a fee schedule hits
        // constantly. HALF_UP would take 0.63 from the creator every time.
        assertThat(azn("12.50").percentage(new BigDecimal("5"))).isEqualTo(azn("0.62"));

        // A rate with its own decimals: 2.9% of 25.00 is 0.725, which rounds to
        // the even neighbour.
        assertThat(azn("25.00").percentage(new BigDecimal("2.9"))).isEqualTo(azn("0.72"));
    }

    @Test
    @DisplayName("a sum of nothing is zero in the currency that was asked for")
    void summingIsTotalAndTyped() {
        assertThat(Money.sum("AZN", List.of())).isEqualTo(Money.zero("AZN"));
        assertThat(Money.sum("AZN", List.of(azn("0.01"), azn("0.02"), azn("19.97")))).isEqualTo(azn("20.00"));
    }

    @Test
    @DisplayName("two currencies are never added, and the refusal names both")
    void mixedCurrenciesAreRefused() {
        Money manat = azn("10.00");
        Money dollars = Money.of(new BigDecimal("10.00"), "USD");

        // Silently coercing here would produce a number in neither currency, and
        // a pledges row has one currency column to record it in. Every operation
        // that compares or combines two amounts refuses, not just addition --
        // "which is bigger, 10 AZN or 10 USD" has no answer either.
        assertThatThrownBy(() -> manat.plus(dollars))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("AZN")
                .hasMessageContaining("USD");
        assertThatThrownBy(() -> manat.minus(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> manat.compareTo(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> manat.isGreaterThan(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> manat.min(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> manat.max(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.sum("AZN", List.of(manat, dollars)))
                .isInstanceOf(CurrencyMismatchException.class);

        // A mismatch is a programming error in the caller, not a validation
        // failure to be reported to a backer, so it stays an IllegalArgument.
        assertThat(new CurrencyMismatchException("AZN", "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero, positive, and negative are asked rather than derived")
    void thePredicatesAgreeWithTheSign() {
        assertThat(Money.zero("AZN").isZero()).isTrue();
        assertThat(Money.zero("AZN").isPositive()).isFalse();
        assertThat(Money.zero("AZN").isNegative()).isFalse();

        assertThat(azn("0.01").isPositive()).isTrue();
        assertThat(azn("-0.01").isNegative()).isTrue();

        // -0.00 is zero. A signum-free implementation comparing against a
        // constructed zero would get this wrong, and a "pledge of nothing" check
        // is exactly where it would matter.
        assertThat(Money.of(new BigDecimal("-0.00"), "AZN").isZero()).isTrue();
    }

    @Test
    @DisplayName("comparison ignores how the amount was spelled")
    void comparisonIsByValue() {
        assertThat(azn("5000").compareTo(azn("5000.00"))).isZero();
        assertThat(azn("5000.01").isGreaterThan(azn("5000.00"))).isTrue();
        assertThat(azn("4999.99").isLessThan(azn("5000.00"))).isTrue();
        assertThat(azn("10.00").min(azn("9.99"))).isEqualTo(azn("9.99"));
        assertThat(azn("10.00").max(azn("9.99"))).isEqualTo(azn("10.00"));

        // equals has to agree with compareTo, or a Money in a Set behaves
        // differently from the same Money in a sorted list.
        assertThat(azn("5000")).isEqualTo(azn("5000.00"));
        assertThat(azn("5000")).hasSameHashCodeAs(azn("5000.00"));
    }

    @Test
    @DisplayName("minor units are exact in both directions")
    void minorUnitsRoundTrip() {
        // What a payment provider is given: an integer number of qapik, never a
        // decimal. Converting at the boundary rather than in each caller is what
        // keeps the conversion one implementation.
        assertThat(azn("12.34").minorUnits()).isEqualTo(1234L);
        assertThat(azn("-12.34").minorUnits()).isEqualTo(-1234L);
        assertThat(Money.ofMinorUnits(1234L, "AZN")).isEqualTo(azn("12.34"));

        // A currency with no minor unit: 500 yen is 500 units, not 50000.
        assertThat(Money.ofMinorUnits(500L, "JPY").minorUnits()).isEqualTo(500L);
    }
}
