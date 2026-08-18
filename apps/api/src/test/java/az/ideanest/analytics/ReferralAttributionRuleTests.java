package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.analytics.domain.LastNonDirectTouch;
import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.analytics.domain.ReferralShares;
import az.ideanest.analytics.domain.ReferralSource;
import az.ideanest.analytics.domain.ReferralTouch;
import az.ideanest.shared.money.CurrencyMismatchException;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The attribution rule itself, and the arithmetic the report is made of.
 *
 * <p>No database and no Spring context: both of the things being checked here are pure
 * functions of their arguments, and starting a PostgreSQL container to assert on one
 * would make the suite slower for no coverage. What needs the database — that the
 * window is applied to rows the query actually returns, that a redelivered event does
 * not write a second row — is {@code ReferralAttributionTests}.
 *
 * <p><strong>The rule under test is "last non-direct touch inside the window".</strong>
 * It is the conventional default and it is a decision rather than an inevitability, so
 * the three ways it could have gone are each pinned by a test: a later direct touch
 * does not overwrite an earlier campaign, an expired touch does not win however recent
 * it was relative to the others, and a touch recorded after the pledge is not evidence
 * about a decision that had already been made.
 */
class ReferralAttributionRuleTests {

    private static final Instant PLEDGED_AT = Instant.parse("2026-08-18T12:00:00Z");

    private static final Duration WINDOW = Duration.ofDays(30);

    private static final UUID PROJECT = UUID.fromString("0198e4a0-0000-7000-8000-000000000001");

    private static final byte[] VISITOR = new byte[] {1, 2, 3};

    @Nested
    @DisplayName("last non-direct touch, inside the window")
    class TheRule {

        @Test
        @DisplayName("the most recent non-direct touch wins")
        void theMostRecentNonDirectTouchWins() {
            ReferralTouch newsletter = touch(social("twitter"), PLEDGED_AT.minus(Duration.ofDays(10)));
            ReferralTouch podcast = touch(social("podcast"), PLEDGED_AT.minus(Duration.ofDays(2)));

            assertThat(LastNonDirectTouch.of(List.of(newsletter, podcast), PLEDGED_AT))
                    .contains(podcast);
        }

        @Test
        @DisplayName("a later direct visit does not overwrite the campaign that earned the pledge")
        void aLaterDirectVisitDoesNotOverwriteTheCampaign() {
            // The whole reason the rule says "non-direct". Somebody who arrives from a
            // newsletter, thinks about it for a day and then types the address in has
            // still been brought here by the newsletter; attributing that pledge to
            // "direct" would report every considered purchase as unattributable.
            ReferralTouch newsletter = touch(email("august-newsletter"), PLEDGED_AT.minus(Duration.ofDays(1)));
            ReferralTouch typedIn = touch(ReferralSource.direct(), PLEDGED_AT.minus(Duration.ofMinutes(5)));

            assertThat(LastNonDirectTouch.of(List.of(newsletter, typedIn), PLEDGED_AT))
                    .contains(newsletter);
        }

        @Test
        @DisplayName("a visitor with nothing but direct touches is attributed to nothing")
        void directOnlyIsAttributedToNothing() {
            ReferralTouch typedIn = touch(ReferralSource.direct(), PLEDGED_AT.minus(Duration.ofHours(1)));

            assertThat(LastNonDirectTouch.of(List.of(typedIn), PLEDGED_AT)).isEmpty();
        }

        @Test
        @DisplayName("no touches at all is attributed to nothing")
        void noTouchesIsAttributedToNothing() {
            assertThat(LastNonDirectTouch.of(List.of(), PLEDGED_AT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the window boundaries")
    class TheBoundaries {

        @Test
        @DisplayName("a touch whose window closes exactly at the pledge has expired")
        void aTouchExpiringAtThePledgeInstantHasExpired() {
            // Exclusive at the far end. A window stated as "thirty days" that also
            // counted the instant it ran out would be thirty days and one moment, and
            // the moment is the one every off-by-one in a retention rule lives in.
            ReferralTouch expiring = touch(social("twitter"), PLEDGED_AT.minus(WINDOW));

            assertThat(expiring.getExpiresAt()).isEqualTo(PLEDGED_AT);
            assertThat(LastNonDirectTouch.of(List.of(expiring), PLEDGED_AT)).isEmpty();
        }

        @Test
        @DisplayName("a touch one moment inside the window still counts")
        void aTouchOneMomentInsideTheWindowCounts() {
            ReferralTouch inside = touch(social("twitter"), PLEDGED_AT.minus(WINDOW).plusMillis(1));

            assertThat(LastNonDirectTouch.of(List.of(inside), PLEDGED_AT)).contains(inside);
        }

        @Test
        @DisplayName("an expired touch loses to an older one that is still open")
        void anExpiredTouchLosesToAnOlderOpenOne() {
            // "Most recent" is only ever applied to the touches that are still
            // evidence. Sorting first and filtering afterwards would attribute this
            // pledge to nothing at all, which is a different answer.
            ReferralTouch open = touch(social("podcast"), PLEDGED_AT.minus(Duration.ofDays(3)));
            ReferralTouch expired = touch(social("twitter"), PLEDGED_AT.minus(WINDOW).minusSeconds(1));

            assertThat(LastNonDirectTouch.of(List.of(open, expired), PLEDGED_AT)).contains(open);
        }

        @Test
        @DisplayName("a touch recorded after the pledge is not evidence about it")
        void aTouchAfterThePledgeIsNotEvidence() {
            // The row can exist: a backer who pledges and then clicks the same
            // campaign's tweet an hour later. Attribution is about what brought
            // somebody to a decision, so nothing recorded after the decision may
            // change it — and without this the report would keep moving under a
            // creator who is reading it.
            ReferralTouch afterwards = touch(social("twitter"), PLEDGED_AT.plusSeconds(1));

            assertThat(LastNonDirectTouch.of(List.of(afterwards), PLEDGED_AT)).isEmpty();
        }

        @Test
        @DisplayName("a touch at exactly the pledge instant still counts")
        void aTouchAtThePledgeInstantCounts() {
            // Inclusive at the near end, exclusive at the far one. The click that
            // opened the checkout page can share a millisecond with the pledge it led
            // to, and dropping it would lose exactly the touches that convert best.
            ReferralTouch atTheMoment = touch(social("twitter"), PLEDGED_AT);

            assertThat(LastNonDirectTouch.of(List.of(atTheMoment), PLEDGED_AT)).contains(atTheMoment);
        }
    }

    @Nested
    @DisplayName("shares of the total")
    class Shares {

        @Test
        @DisplayName("shares are exact percentages of the total value")
        void sharesArePercentagesOfTheTotal() {
            List<BigDecimal> shares = ReferralShares.of(List.of(azn("75.00"), azn("25.00")));

            assertThat(shares).containsExactly(new BigDecimal("75.00"), new BigDecimal("25.00"));
        }

        @Test
        @DisplayName("shares add up to exactly one hundred even when no part divides evenly")
        void sharesAddUpToExactlyOneHundred() {
            // Three equal parts is 33.333…% each. Rounding each one on its own gives
            // 99.99, and a creator reading a report whose shares do not add up stops
            // trusting the numbers above them. The remainder is handed out instead.
            List<BigDecimal> shares = ReferralShares.of(List.of(azn("10.00"), azn("10.00"), azn("10.00")));

            assertThat(shares).containsExactly(
                    new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
            assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("seven parts of an awkward total still add up to exactly one hundred")
        void sevenAwkwardPartsStillAddUp() {
            List<Money> values = List.of(
                    azn("1.03"), azn("2.07"), azn("0.01"), azn("11.11"), azn("0.99"), azn("5.00"), azn("0.02"));

            assertThat(ReferralShares.of(values).stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("a total of nothing is every share of nothing, not a division by zero")
        void aTotalOfNothingIsEveryShareOfNothing() {
            assertThat(ReferralShares.of(List.of(azn("0.00"), azn("0.00"))))
                    .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("nothing to share is no shares")
        void nothingToShareIsNoShares() {
            assertThat(ReferralShares.of(List.of())).isEmpty();
        }

        @Test
        @DisplayName("two currencies are refused rather than added together")
        void twoCurrenciesAreRefused() {
            // Money's rule, unchanged by being in a report: there is no exchange rate
            // that makes "40% of the value" true of a mixed total.
            assertThatThrownBy(() -> ReferralShares.of(List.of(azn("10.00"), Money.of(new BigDecimal("10.00"), "USD"))))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("a negative value is refused")
        void aNegativeValueIsRefused() {
            // A share of a negative part has no meaning as a percentage of a total
            // that includes it, and an attributed pledge is never negative. A refusal
            // here is a bug found; a number is a bug shipped.
            assertThatThrownBy(() -> ReferralShares.of(List.of(azn("10.00"), azn("-1.00"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what a source may say")
    class Sources {

        @Test
        @DisplayName("a direct visit carries no source, campaign or code")
        void aDirectVisitCarriesNothingElse() {
            ReferralSource direct = ReferralSource.direct();

            assertThat(direct.isDirect()).isTrue();
            assertThat(direct.source()).isNull();
            assertThat(direct.campaign()).isNull();
            assertThat(direct.referrerCode()).isNull();
        }

        @Test
        @DisplayName("labels are folded, so one source is one row in the report")
        void labelsAreFolded() {
            // "Twitter", "twitter" and " twitter " are one source. Without the fold
            // they are three rows, three shares, and a top-sources list whose first
            // three entries are the same place.
            ReferralSource source = ReferralSource.of(ReferralChannel.SOCIAL, "  Twitter  ", " Launch-Week ", null);

            assertThat(source.source()).isEqualTo("twitter");
            assertThat(source.campaign()).isEqualTo("launch-week");
        }

        @Test
        @DisplayName("a referrer code keeps its case, because it is a token and not a label")
        void aReferrerCodeKeepsItsCase() {
            ReferralSource source =
                    ReferralSource.of(ReferralChannel.REFERRAL_LINK, null, null, "  aB7-xY9_Qz  ");

            assertThat(source.referrerCode()).isEqualTo("aB7-xY9_Qz");
            assertThat(source.isDirect()).isFalse();
        }

        @Test
        @DisplayName("a referral link with no code is refused")
        void aReferralLinkWithNoCodeIsRefused() {
            assertThatThrownBy(() -> ReferralSource.of(ReferralChannel.REFERRAL_LINK, "somewhere", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a non-direct visit that names nothing is refused")
        void aNonDirectVisitThatNamesNothingIsRefused() {
            // A row saying only "SOCIAL" is a row the report cannot label, and it
            // would be the easiest possible way to fill the table with noise.
            assertThatThrownBy(() -> ReferralSource.of(ReferralChannel.SOCIAL, "   ", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a direct visit that names a source is refused rather than quietly trimmed")
        void aDirectVisitThatNamesASourceIsRefused() {
            assertThatThrownBy(() -> ReferralSource.of(ReferralChannel.DIRECT, "twitter", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an over-long label is refused rather than truncated")
        void anOverLongLabelIsRefused() {
            String tooLong = "s".repeat(ReferralSource.MAX_LENGTH + 1);

            assertThatThrownBy(() -> ReferralSource.of(ReferralChannel.SOCIAL, tooLong, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ReferralTouch touch(ReferralSource source, Instant occurredAt) {
        return ReferralTouch.record(PROJECT, VISITOR, null, source, occurredAt, occurredAt.plus(WINDOW));
    }

    private static ReferralSource social(String source) {
        return ReferralSource.of(ReferralChannel.SOCIAL, source, null, null);
    }

    private static ReferralSource email(String campaign) {
        return ReferralSource.of(ReferralChannel.EMAIL, "newsletter", campaign, null);
    }

    private static Money azn(String amount) {
        return Money.of(new BigDecimal(amount), "AZN");
    }
}
