package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Splitting money without losing or inventing a minor unit.
 *
 * <p>The classic failure: 0.05 divided three ways rounds to 0.02 three times, the
 * parts come to 0.06, and a payout is now one qapik larger than the money that was
 * collected. Division of money is not division, it is allocation, and the invariant
 * that makes it allocation is that <strong>the parts always add up to the whole</strong>.
 * Every test here asserts that invariant rather than a particular arrangement of
 * the remainder, except where the arrangement itself is the documented rule.
 */
class MoneyAllocationTests {

    private static Money azn(String amount) {
        return Money.of(new BigDecimal(amount), "AZN");
    }

    @Test
    @DisplayName("a remainder is handed out one minor unit at a time, to the earliest parts")
    void theRemainderIsDistributed() {
        // 0.05 in three: 0.0166... each. Rounding each share independently gives
        // 0.02 three times, which is 0.06 -- money that was never collected.
        assertThat(azn("0.05").allocate(3)).containsExactly(azn("0.02"), azn("0.02"), azn("0.01"));

        assertThat(azn("100.00").allocate(3)).containsExactly(azn("33.34"), azn("33.33"), azn("33.33"));
    }

    @Test
    @DisplayName("an even split is even")
    void anEvenSplitHasNoRemainder() {
        assertThat(azn("10.00").allocate(4)).containsExactly(azn("2.50"), azn("2.50"), azn("2.50"), azn("2.50"));
        assertThat(azn("10.00").allocate(1)).containsExactly(azn("10.00"));
    }

    @Test
    @DisplayName("a whole that is smaller than the number of parts leaves parts of nothing")
    void theSmallestWholeStillAddsUp() {
        // Not an error: three creators splitting one qapik is a real state, and
        // the honest answer is that two of them get nothing rather than that the
        // platform invents two qapik.
        assertThat(azn("0.01").allocate(3)).containsExactly(azn("0.01"), Money.zero("AZN"), Money.zero("AZN"));
        assertThat(Money.zero("AZN").allocate(3))
                .containsExactly(Money.zero("AZN"), Money.zero("AZN"), Money.zero("AZN"));
    }

    @Test
    @DisplayName("weights split in proportion, and still add up")
    void weightedAllocationAddsUp() {
        // A fee split: 30% one way, 70% the other, of an amount that divides
        // into neither.
        List<Money> split = azn("0.05").allocate(3, 7);

        assertThat(split).containsExactly(azn("0.02"), azn("0.03"));
        assertThat(Money.sum("AZN", split)).isEqualTo(azn("0.05"));

        // A zero weight takes nothing, and does not disturb the rest.
        assertThat(azn("10.00").allocate(1, 0, 1)).containsExactly(azn("5.00"), Money.zero("AZN"), azn("5.00"));
    }

    @Test
    @DisplayName("a negative whole splits into negative parts that add up to it")
    void aRefundSplitsToo() {
        // Reversing a distribution has to give back exactly what was distributed,
        // which fails the moment the remainder is handed out by truncation in one
        // direction and by rounding in the other.
        List<Money> parts = azn("-0.05").allocate(3);

        assertThat(parts).containsExactly(azn("-0.02"), azn("-0.02"), azn("-0.01"));
        assertThat(Money.sum("AZN", parts)).isEqualTo(azn("-0.05"));
    }

    @Test
    @DisplayName("allocation uses the currency's scale, not two decimal places")
    void allocationRespectsTheCurrencyScale() {
        Money hundredYen = Money.of(new BigDecimal("100"), "JPY");

        assertThat(hundredYen.allocate(3))
                .containsExactly(
                        Money.of(new BigDecimal("34"), "JPY"),
                        Money.of(new BigDecimal("33"), "JPY"),
                        Money.of(new BigDecimal("33"), "JPY"));
    }

    @Test
    @DisplayName("nothing can be split into no parts, or into parts of unstated size")
    void impossibleAllocationsAreRefused() {
        assertThatThrownBy(() -> azn("10.00").allocate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> azn("10.00").allocate(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> azn("10.00").allocate(new long[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> azn("10.00").allocate(1, -1)).isInstanceOf(IllegalArgumentException.class);
        // Every weight zero has no proportion in it at all, so there is no split
        // to compute -- and returning zeroes would lose the whole amount.
        assertThatThrownBy(() -> azn("10.00").allocate(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the parts add up to the whole for every amount and every number of parts")
    void thePartsAlwaysAddUpToTheWhole() {
        // The invariant, checked exhaustively rather than at the three values
        // somebody thought of: every amount from 0.00 to 5.00 and every split
        // from 1 to 7 parts, in both directions of sign. This is the
        // property-based test §20.2 asks for, written against the whole input
        // space rather than a sample of it, because the space is small enough to
        // enumerate and a generator would only be sampling from it.
        for (int minorUnits = 0; minorUnits <= 500; minorUnits++) {
            for (int parts = 1; parts <= 7; parts++) {
                for (int sign : new int[] {1, -1}) {
                    Money whole = Money.ofMinorUnits((long) sign * minorUnits, "AZN");
                    List<Money> allocated = whole.allocate(parts);

                    assertThat(allocated).hasSize(parts);
                    assertThat(Money.sum("AZN", allocated))
                            .withFailMessage("%s split %d ways came to %s", whole, parts, allocated)
                            .isEqualTo(whole);

                    // No part is further than one minor unit from any other, so a
                    // remainder is spread rather than dumped on one recipient.
                    long largest = allocated.stream()
                            .mapToLong(Money::minorUnits)
                            .max()
                            .orElseThrow();
                    long smallest = allocated.stream()
                            .mapToLong(Money::minorUnits)
                            .min()
                            .orElseThrow();
                    assertThat(Math.abs(largest - smallest))
                            .withFailMessage("%s split %d ways is lopsided: %s", whole, parts, allocated)
                            .isLessThanOrEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName("a weighted split adds up for every amount and every pair of weights")
    void weightedPartsAlwaysAddUpToTheWhole() {
        for (int minorUnits = 0; minorUnits <= 200; minorUnits++) {
            for (int first = 0; first <= 5; first++) {
                for (int second = 0; second <= 5; second++) {
                    if (first + second == 0) {
                        continue;
                    }
                    Money whole = Money.ofMinorUnits(minorUnits, "AZN");
                    List<Money> allocated = whole.allocate(first, second);

                    assertThat(Money.sum("AZN", allocated))
                            .withFailMessage("%s split %d:%d came to %s", whole, first, second, allocated)
                            .isEqualTo(whole);
                }
            }
        }
    }
}
