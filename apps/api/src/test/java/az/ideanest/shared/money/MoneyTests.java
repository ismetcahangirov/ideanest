package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an amount of money is, and what it refuses to be.
 *
 * <p>{@code CLAUDE.md} puts money in the set of things that are not optional to
 * test, for the reason it gives: these failures are silent and expensive. The wire
 * format lives in {@link MoneyJsonTests}, the arithmetic in
 * {@link MoneyArithmeticTests} and {@link MoneyAllocationTests}, and the one
 * statement of the rounding rule in {@link MoneyRoundingTests}. This suite is the
 * value object itself.
 */
class MoneyTests {

    @Test
    @DisplayName("an amount is padded to the currency's minor unit")
    void theScaleIsNormalised() {
        // A client that sent 5000 must not be told the value changed when it reads
        // "5000.00" back, and a client comparing the two strings is the normal case
        // in an autosaving editor.
        assertThat(Money.of(new BigDecimal("5000"), "AZN").amount()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(Money.of(new BigDecimal("5000.5"), "AZN").amount()).isEqualTo(new BigDecimal("5000.50"));
    }

    @Test
    @DisplayName("a place the currency does not have is refused rather than rounded")
    void anUnrepresentableAmountIsRefused() {
        // numeric(14,2) would round this silently. Silently turning 5000.555 into
        // 5000.56 is a small error today and an unreconcilable ledger the first time
        // it happens on a charge. An amount that arrived from outside is refused;
        // only a value this code computed is rounded, and MoneyRounding is where
        // that rule is stated.
        assertThatThrownBy(() -> Money.of(new BigDecimal("5000.555"), "AZN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");

        // Trailing zeros are the same amount, not a third decimal place.
        assertThatCode(() -> Money.of(new BigDecimal("5000.500"), "AZN")).doesNotThrowAnyException();

        // The rule is the currency's minor unit and not the number two: there is
        // no such amount as 100.50 yen.
        assertThatThrownBy(() -> Money.of(new BigDecimal("100.50"), "JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 0 decimal places");
        assertThat(Money.of(new BigDecimal("100"), "JPY").amount()).isEqualTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("a currency is a three-letter code, normalised")
    void theCurrencyIsAnIsoCode() {
        assertThat(Money.of(BigDecimal.ONE, "azn").currency()).isEqualTo("AZN");

        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "manat")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.of(null, "AZN")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a currency the columns cannot hold is refused at construction")
    void aCurrencyWithThreeMinorUnitsIsRefused() {
        // §7.3 fixes every money column at numeric(14,2). A dinar has three minor
        // units, so accepting one here would mean PostgreSQL rounding a charge.
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "KWD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KWD");
    }

    @Test
    @DisplayName("no amount is no money at all")
    void anAbsentAmountIsNull() {
        // Every mapping from a draft campaign hits this: a goal that has not been
        // set is not zero money, it is no money.
        assertThat(Money.orNull(null, "AZN")).isNull();
        assertThat(Money.orNull(new BigDecimal("10"), "AZN")).isEqualTo(Money.of(new BigDecimal("10.00"), "AZN"));
    }

    @Test
    @DisplayName("zero is an amount, and it is an amount in a currency")
    void zeroIsTyped() {
        assertThat(Money.zero("AZN").amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(Money.zero("azn")).isEqualTo(Money.zero("AZN"));
        assertThat(Money.zero("JPY").amount()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("an amount reads as the amount and its currency, not as a bare number")
    void toStringCarriesTheCurrency() {
        // Half the money bugs in a log are an amount whose currency was assumed.
        assertThat(Money.of(new BigDecimal("5000"), "AZN")).hasToString("5000.00 AZN");
    }
}
