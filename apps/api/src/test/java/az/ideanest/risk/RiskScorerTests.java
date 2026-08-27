package az.ideanest.risk;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.risk.application.RiskFinding;
import az.ideanest.risk.application.RiskInputs;
import az.ideanest.risk.application.RiskScore;
import az.ideanest.risk.application.RiskScorer;
import az.ideanest.risk.domain.RiskDecision;
import az.ideanest.risk.domain.RiskSignal;
import az.ideanest.risk.domain.SignalOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §17.2's fraud signals, as arithmetic — issue #108.
 *
 * <p>No Spring, no database, no clock. {@code RiskScorer} takes {@link RiskInputs} and
 * nothing else, which is the whole reason it is a separate class: the weights and
 * thresholds are configuration precisely so somebody can tune them after watching a month
 * of chargebacks, and tuning is only safe if every branch is covered.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #oneSignalNeverReachesTheQueue()} — each of these fires on behaviour that is
 *       unremarkable alone. A queue that flagged every new account is a queue nobody reads
 *       by the second week.
 *   <li>{@link #anUnavailableSignalIsNotAClearOne()} — the distinction the whole
 *       {@code SignalOutcome} enum exists for. A low score with two unavailable signals is
 *       a different statement from a low score with none.
 *   <li>{@link #aFirstPledgeIsNotEvidenceAgainstItself()} — the off-by-one that would have
 *       flagged every backer on the platform.
 * </ul>
 */
@DisplayName("Fraud signals")
class RiskScorerTests {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private final RiskProperties properties = new RiskProperties(null, null, null, 0);

    private final RiskScorer scorer = new RiskScorer(properties);

    // ------------------------------------------------------------------
    // The quiet case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an ordinary pledge scores nothing worth a person's time")
    void anOrdinaryPledgeIsAllowed() {
        RiskScore score = scorer.score(inputs().build());

        assertThat(score.score()).isZero();
        assertThat(score.decision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    @DisplayName("records every signal it considered, not only the ones that fired")
    void everySignalIsReported() {
        RiskScore score = scorer.score(inputs().build());

        // "Which signals were even considered" is the second question anybody asks about a
        // flagged pledge, and a findings list of only the failures cannot answer it.
        assertThat(score.findings()).extracting(RiskFinding::signal).containsExactlyInAnyOrder(RiskSignal.values());
    }

    // ------------------------------------------------------------------
    // Velocity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fires on an account making pledges faster than the threshold")
    void accountVelocityFires() {
        RiskScore score = scorer.score(inputs().recentByAccount(6).build());

        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ACCOUNT)).isEqualTo(SignalOutcome.FIRED);
        assertThat(score.score()).isEqualTo(properties.weights().pledgeVelocityAccount());
    }

    @Test
    @DisplayName("a first pledge is not evidence against itself")
    void aFirstPledgeIsNotEvidenceAgainstItself() {
        // The count is OTHER pledges in the window. Without that exclusion a threshold of
        // one would fire on every backer's first pledge on the platform.
        RiskScore score = scorer.score(inputs().recentByAccount(0).build());

        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ACCOUNT)).isEqualTo(SignalOutcome.CLEAR);
    }

    @Test
    @DisplayName("the address threshold is looser than the account one, because an address is a household")
    void addressVelocityIsLooser() {
        // Ten pledges from one address is an office. Ten from one account is not.
        RiskScore score = scorer.score(inputs().recentByAddress(10).build());

        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ADDRESS)).isEqualTo(SignalOutcome.CLEAR);
        assertThat(properties.velocity().pledgesPerAddress())
                .isGreaterThan(properties.velocity().pledgesPerAccount());
    }

    @Test
    @DisplayName("catches the pattern that defeats per-account velocity")
    void addressVelocityCatchesSpreadAccounts() {
        // Twelve accounts making one pledge each is invisible per account and obvious per
        // address, which is why both signals exist and neither subsumes the other.
        RiskScore score = scorer.score(inputs().recentByAccount(1).recentByAddress(12).build());

        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ACCOUNT)).isEqualTo(SignalOutcome.CLEAR);
        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ADDRESS)).isEqualTo(SignalOutcome.FIRED);
    }

    // ------------------------------------------------------------------
    // Account age and address familiarity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an account created hours ago is new; one created last week is not")
    void newAccountFiresOnHours() {
        assertThat(outcome(scorer.score(inputs().accountAge(Duration.ofHours(2)).build()), RiskSignal.NEW_ACCOUNT))
                .isEqualTo(SignalOutcome.FIRED);
        assertThat(outcome(scorer.score(inputs().accountAge(Duration.ofDays(7)).build()), RiskSignal.NEW_ACCOUNT))
                .isEqualTo(SignalOutcome.CLEAR);
    }

    @Test
    @DisplayName("a new account alone is not enough to be looked at")
    void oneSignalNeverReachesTheQueue() {
        // Everybody's account is new once. A platform that treated newness as guilt would
        // be flagging its own growth.
        RiskScore score = scorer.score(inputs().accountAge(Duration.ofHours(1)).build());

        assertThat(score.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(score.score()).isLessThan(properties.reviewAtScore());
    }

    @Test
    @DisplayName("fires on an address this account has never been seen from")
    void unfamiliarAddressFires() {
        RiskScore score = scorer.score(
                inputs().from("203.0.113.9").known(Set.of("198.51.100.4", "198.51.100.5")).build());

        assertThat(outcome(score, RiskSignal.UNFAMILIAR_ADDRESS)).isEqualTo(SignalOutcome.FIRED);
    }

    @Test
    @DisplayName("does not fire on an account with no addresses on record")
    void unfamiliarAddressNeedsSomethingToCompareTo() {
        // A first session is unfamiliar by definition. Firing on it would make this a
        // second, worse copy of the new-account signal.
        RiskScore score = scorer.score(inputs().from("203.0.113.9").known(Set.of()).build());

        assertThat(outcome(score, RiskSignal.UNFAMILIAR_ADDRESS)).isEqualTo(SignalOutcome.CLEAR);
    }

    @Test
    @DisplayName("never puts the address itself in a finding")
    void findingsCarryNoAddress() {
        RiskScore score = scorer.score(
                inputs().from("203.0.113.9").known(Set.of("198.51.100.4")).build());

        // §17.4: these end up in a jsonb column read by people who are not the person.
        assertThat(score.findings())
                .allSatisfy(finding ->
                        assertThat(finding.detail() == null ? "" : finding.detail()).doesNotContain("203.0.113.9"));
    }

    // ------------------------------------------------------------------
    // Availability — the distinction the whole enum exists for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unavailable signal is not a clear one")
    void anUnavailableSignalIsNotAClearOne() {
        RiskScore score = scorer.score(inputs().build());

        // Geography is unavailable on every deployment: no IP-to-country source is
        // configured, and `UnresolvedAddressGeography` is named for exactly that.
        assertThat(outcome(score, RiskSignal.GEOGRAPHY_MISMATCH)).isEqualTo(SignalOutcome.UNAVAILABLE);
        assertThat(score.signalsUnavailable()).isPositive();
    }

    @Test
    @DisplayName("an unavailable signal contributes nothing to the score")
    void anUnavailableSignalScoresNothing() {
        RiskScore score = scorer.score(inputs().build());

        assertThat(score.score()).isZero();
    }

    @Test
    @DisplayName("reports the two address signals unavailable when there is no address")
    void noAddressMakesTwoSignalsUnavailable() {
        // A pledge assessed with no session behind it. Reporting "no pledges from this
        // address" would be a fact nobody established.
        RiskScore score = scorer.score(inputs().noAddress().build());

        assertThat(outcome(score, RiskSignal.PLEDGE_VELOCITY_ADDRESS)).isEqualTo(SignalOutcome.UNAVAILABLE);
        assertThat(outcome(score, RiskSignal.UNFAMILIAR_ADDRESS)).isEqualTo(SignalOutcome.UNAVAILABLE);
        assertThat(score.signalsUnavailable()).isEqualTo(3);
    }

    @Test
    @DisplayName("reports the age signal unavailable rather than treating the account as old")
    void unknownAccountAgeIsUnavailable() {
        RiskScore score = scorer.score(inputs().unknownAccountAge().build());

        assertThat(outcome(score, RiskSignal.NEW_ACCOUNT)).isEqualTo(SignalOutcome.UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // The decision
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two heavy signals reach the queue")
    void twoSignalsReachTheQueue() {
        RiskScore score = scorer.score(inputs().recentByAccount(6).recentByAddress(12).build());

        assertThat(score.decision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    @DisplayName("never blocks, whatever fires")
    void nothingEverBlocks() {
        RiskScore score = scorer.score(inputs()
                .recentByAccount(50)
                .recentByAddress(50)
                .accountAge(Duration.ofMinutes(1))
                .from("203.0.113.9")
                .known(Set.of("198.51.100.4"))
                .build());

        // Blocking a pledge on an automated score needs a measured false-positive rate and
        // an appeal path, and this platform has neither. RiskAssessments argues it.
        assertThat(score.decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(score.decision()).isNotEqualTo(RiskDecision.BLOCK);
    }

    @Test
    @DisplayName("clamps at 100 rather than running past it")
    void theScoreIsClamped() {
        RiskScore score = scorer.score(inputs()
                .recentByAccount(50)
                .recentByAddress(50)
                .accountAge(Duration.ofMinutes(1))
                .from("203.0.113.9")
                .known(Set.of("198.51.100.4"))
                .build());

        assertThat(score.score()).isLessThanOrEqualTo(RiskScorer.MAX_SCORE);
    }

    @Test
    @DisplayName("keeps every signal's weight even when it did not fire")
    void weightsSurviveAClearSignal() {
        // The queue shows what an assessment was capable of scoring, and an old assessment
        // has to stay readable after somebody has tuned the numbers.
        RiskScore score = scorer.score(inputs().build());

        assertThat(score.findings()).allSatisfy(finding -> assertThat(finding.weight()).isPositive());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static SignalOutcome outcome(RiskScore score, RiskSignal signal) {
        return score.findings().stream()
                .filter(finding -> finding.signal() == signal)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding for " + signal))
                .outcome();
    }

    private static Inputs inputs() {
        return new Inputs();
    }

    /** A quiet pledge, which every test then makes noisy in one way. */
    private static final class Inputs {

        private Optional<String> address = Optional.of("198.51.100.4");
        private Optional<Instant> createdAt = Optional.of(NOW.minus(Duration.ofDays(30)));
        private Set<String> known = Set.of("198.51.100.4");
        private int byAccount;
        private int byAddress;

        Inputs recentByAccount(int count) {
            this.byAccount = count;
            return this;
        }

        Inputs recentByAddress(int count) {
            this.byAddress = count;
            return this;
        }

        Inputs accountAge(Duration age) {
            this.createdAt = Optional.of(NOW.minus(age));
            return this;
        }

        Inputs unknownAccountAge() {
            this.createdAt = Optional.empty();
            return this;
        }

        Inputs from(String value) {
            this.address = Optional.of(value);
            return this;
        }

        Inputs noAddress() {
            this.address = Optional.empty();
            return this;
        }

        Inputs known(Set<String> addresses) {
            this.known = addresses;
            return this;
        }

        RiskInputs build() {
            return new RiskInputs(
                    NOW,
                    address,
                    createdAt,
                    known,
                    byAccount,
                    byAddress,
                    // Empty on every deployment: see UnresolvedAddressGeography.
                    Optional.empty(),
                    Optional.of("AZ"));
        }
    }
}
