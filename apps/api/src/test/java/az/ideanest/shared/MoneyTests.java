package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Money on the wire and money in memory.
 *
 * <p>{@code CLAUDE.md} puts money arithmetic in the set of things that are not
 * optional to test, for the reason it gives: these failures are silent and
 * expensive. A goal serialised as a JSON number is read by a client as a double
 * and nothing anywhere reports an error; the discrepancy appears later, in a total
 * that does not add up.
 */
class MoneyTests {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("the amount serialises as a string, never as a number")
    void theAmountIsAStringOnTheWire() throws Exception {
        String body = json.writeValueAsString(Money.of(new BigDecimal("5000.00"), "AZN"));

        // §10.3. A JSON number here is an IEEE 754 double in every mainstream
        // parser, and on a funding platform the value being approximated is
        // somebody's pledge.
        assertThat(body).isEqualTo("{\"amount\":\"5000.00\",\"currency\":\"AZN\"}");
    }

    @Test
    @DisplayName("an amount sent as a string is read back exactly")
    void anAmountRoundTrips() throws Exception {
        Money money = json.readValue("{\"amount\":\"1234.56\",\"currency\":\"AZN\"}", Money.class);

        assertThat(money.amount()).isEqualTo(new BigDecimal("1234.56"));
        assertThat(money.currency()).isEqualTo("AZN");
    }

    @Test
    @DisplayName("an amount is padded to the scale of the column")
    void theScaleIsNormalised() {
        // A client that sent 5000 must not be told the value changed when it reads
        // "5000.00" back, and a client comparing the two strings is the normal case
        // in an autosaving editor.
        assertThat(Money.of(new BigDecimal("5000"), "AZN").amount()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(Money.of(new BigDecimal("5000.5"), "AZN").amount()).isEqualTo(new BigDecimal("5000.50"));
    }

    @Test
    @DisplayName("a third decimal place is refused rather than rounded")
    void anUnrepresentableAmountIsRefused() {
        // numeric(14,2) would round this silently. Silently turning 5000.555 into
        // 5000.56 is a small error today and an unreconcilable ledger the first time
        // it happens on a charge.
        assertThatThrownBy(() -> Money.of(new BigDecimal("5000.555"), "AZN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");

        // Trailing zeros are the same amount, not a third decimal place.
        assertThatCode(() -> Money.of(new BigDecimal("5000.500"), "AZN")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a currency is a three-letter code, normalised")
    void theCurrencyIsAnIsoCode() {
        assertThat(Money.of(BigDecimal.ONE, "azn").currency()).isEqualTo("AZN");

        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "manat")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no amount is no money at all")
    void anAbsentAmountIsNull() {
        // Every mapping from a draft campaign hits this: a goal that has not been
        // set is not zero money, it is no money.
        assertThat(Money.orNull(null, "AZN")).isNull();
        assertThat(Money.orNull(new BigDecimal("10"), "AZN")).isEqualTo(Money.of(new BigDecimal("10.00"), "AZN"));
    }
}
