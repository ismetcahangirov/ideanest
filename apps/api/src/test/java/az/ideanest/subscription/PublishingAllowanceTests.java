package az.ideanest.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.access.PublishingAllowance;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What crosses the module boundary, and the two conventions a caller must not get wrong.
 *
 * <p>Null means "no limit", and an unsubscribed allowance carries nothing. Both are
 * enforced by the type rather than trusted, because a caller that read the numbers before
 * the flag would see "no limits" where the truth is "no permission" — which is a creator
 * publishing without a plan.
 */
class PublishingAllowanceTests {

    @Test
    @DisplayName("an account with no subscription is permitted nothing at all")
    void unsubscribedIsRefused() {
        assertThat(PublishingAllowance.NONE.permitsAnother(0)).isFalse();
        assertThat(PublishingAllowance.NONE.permitsGoal(new BigDecimal("1.00"), "AZN")).isFalse();
    }

    @Test
    @DisplayName("refuses to be constructed unsubscribed with limits on it")
    void unsubscribedCarriesNoLimits() {
        assertThatThrownBy(() -> new PublishingAllowance(false, "GROWTH", 3, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a goal ceiling with no currency to compare it in")
    void aCeilingNeedsACurrency() {
        assertThatThrownBy(() -> new PublishingAllowance(true, "GROWTH", 3, new BigDecimal("100.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null campaign limit means no limit, not zero")
    void nullMeansUnlimited() {
        PublishingAllowance pro = new PublishingAllowance(true, "PRO", null, null, null);

        assertThat(pro.permitsAnother(0)).isTrue();
        assertThat(pro.permitsAnother(4_000)).isTrue();
        assertThat(pro.permitsGoal(new BigDecimal("9999999.00"), "AZN")).isTrue();
    }

    @Test
    @DisplayName("permits exactly as many campaigns as the plan says, and not one more")
    void theCampaignLimitBitesAtTheBoundary() {
        PublishingAllowance starter = new PublishingAllowance(true, "STARTER", 1, null, null);

        assertThat(starter.permitsAnother(0)).isTrue();
        assertThat(starter.permitsAnother(1)).isFalse();
    }

    @Test
    @DisplayName("permits a goal equal to the ceiling and refuses one above it")
    void theGoalCeilingIsInclusive() {
        PublishingAllowance starter = new PublishingAllowance(true, "STARTER", 1, new BigDecimal("10000.00"), "AZN");

        assertThat(starter.permitsGoal(new BigDecimal("10000.00"), "AZN")).isTrue();
        assertThat(starter.permitsGoal(new BigDecimal("10000.01"), "AZN")).isFalse();
    }

    @Test
    @DisplayName("passes a campaign with no goal, because the checklist refuses that a line later")
    void aMissingGoalIsNotThisRule() {
        PublishingAllowance starter = new PublishingAllowance(true, "STARTER", 1, new BigDecimal("10000.00"), "AZN");

        // Refusing here would send a creator to the pricing page over an empty field.
        assertThat(starter.permitsGoal(null, "AZN")).isTrue();
    }

    @Test
    @DisplayName("does not compare across currencies, because §21.2 gives nothing to convert with")
    void currenciesAreNotConverted() {
        PublishingAllowance starter = new PublishingAllowance(true, "STARTER", 1, new BigDecimal("10000.00"), "AZN");

        assertThat(starter.permitsGoal(new BigDecimal("999999.00"), "EUR")).isTrue();
    }
}
