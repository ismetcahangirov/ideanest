package az.ideanest.risk.application;

import az.ideanest.risk.RiskProperties;
import az.ideanest.risk.domain.RiskDecision;
import az.ideanest.risk.domain.RiskSignal;
import az.ideanest.risk.domain.SignalOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Facts to a score — issue #108, and the only class here worth testing exhaustively.
 *
 * <h2>Why this is pure</h2>
 *
 * <p>It reads no database, holds no clock, and calls nothing. Everything it needs arrives
 * as {@link RiskInputs}, which means every branch below can be exercised by constructing
 * one — and a scoring rule that can only be tested by seeding four tables is a scoring rule
 * that gets tested once.
 *
 * <p>That matters more here than it would for most logic, because the numbers are going to
 * move. The weights and thresholds are configuration precisely so somebody can tune them
 * after watching a month of chargebacks, and the tuning is safe only if the arithmetic is
 * covered.
 *
 * <h2>Additive, capped, and deliberately not multiplicative</h2>
 *
 * <p>Each signal that fires contributes its weight; the total is clamped to 100. The
 * alternative — multiplying factors together — produces a number nobody can decompose, and
 * the first question anybody asks about a flagged pledge is <em>which signal fired</em>.
 * With addition the answer is the findings list, and removing a weight from the total is
 * subtraction.
 *
 * <h2>An unavailable signal contributes nothing and is counted</h2>
 *
 * <p>Not zero-and-forgotten. See {@link SignalOutcome}: a low score with two unavailable
 * signals is a different statement from a low score with none, and the queue shows both.
 */
@Component
public class RiskScorer {

    /** The ceiling. A score is an ordering rather than a probability. */
    public static final int MAX_SCORE = 100;

    private final RiskProperties properties;

    public RiskScorer(RiskProperties properties) {
        this.properties = properties;
    }

    /** One assessment, from facts somebody else gathered. */
    public RiskScore score(RiskInputs inputs) {
        List<RiskFinding> findings = new ArrayList<>();

        findings.add(pledgeVelocityAccount(inputs));
        findings.add(pledgeVelocityAddress(inputs));
        findings.add(newAccount(inputs));
        findings.add(unfamiliarAddress(inputs));
        findings.add(geographyMismatch(inputs));

        int total = 0;
        int unavailable = 0;
        for (RiskFinding finding : findings) {
            if (finding.outcome() == SignalOutcome.FIRED) {
                total += finding.weight();
            } else if (finding.outcome() == SignalOutcome.UNAVAILABLE) {
                unavailable++;
            }
        }

        int score = Math.min(total, MAX_SCORE);
        return new RiskScore(score, decisionFor(score), List.copyOf(findings), unavailable);
    }

    /**
     * Card testing: a stolen card tried against small pledges in quick succession.
     *
     * <p>The count is <em>other</em> pledges in the window, so the pledge being assessed is
     * not evidence against itself — an account's first ever pledge would otherwise arrive
     * with a count of one and a threshold of one would fire on everybody.
     */
    private RiskFinding pledgeVelocityAccount(RiskInputs inputs) {
        RiskProperties.Velocity velocity = properties.velocity();
        int weight = properties.weights().pledgeVelocityAccount();

        if (inputs.recentPledgesByAccount() >= velocity.pledgesPerAccount()) {
            return RiskFinding.fired(
                    RiskSignal.PLEDGE_VELOCITY_ACCOUNT,
                    weight,
                    "%d pledges in %s".formatted(inputs.recentPledgesByAccount(), window(velocity.window())));
        }
        return RiskFinding.clear(RiskSignal.PLEDGE_VELOCITY_ACCOUNT, weight);
    }

    /**
     * The pattern that defeats the signal above: ten accounts making one pledge each.
     *
     * <p>Unavailable rather than clear when the source address is not known — a pledge
     * assessed from a background job has no request behind it, and reporting that as "no
     * pledges from this address" would be a fact nobody established.
     */
    private RiskFinding pledgeVelocityAddress(RiskInputs inputs) {
        RiskProperties.Velocity velocity = properties.velocity();
        int weight = properties.weights().pledgeVelocityAddress();

        if (inputs.sourceAddress().isEmpty()) {
            return RiskFinding.unavailable(RiskSignal.PLEDGE_VELOCITY_ADDRESS, weight, "no source address");
        }
        if (inputs.recentPledgesByAddress() >= velocity.pledgesPerAddress()) {
            return RiskFinding.fired(
                    RiskSignal.PLEDGE_VELOCITY_ADDRESS,
                    weight,
                    "%d pledges from this address in %s"
                            .formatted(inputs.recentPledgesByAddress(), window(velocity.window())));
        }
        return RiskFinding.clear(RiskSignal.PLEDGE_VELOCITY_ADDRESS, weight);
    }

    /**
     * How old the account is, in hours.
     *
     * <p>Hours and not days, and the smallest weight of the four. Everybody's account is
     * new once and a platform that treated newness as guilt would be flagging its own
     * growth; what this is for is to lift a pledge that fired something else out of the
     * noise, not to stand on its own.
     */
    private RiskFinding newAccount(RiskInputs inputs) {
        int weight = properties.weights().newAccount();

        if (inputs.accountCreatedAt().isEmpty()) {
            // An account with no creation time is a row that should not exist. Reporting
            // it as unavailable rather than as an ancient account is the safe direction.
            return RiskFinding.unavailable(RiskSignal.NEW_ACCOUNT, weight, "account age unknown");
        }

        Duration age = Duration.between(inputs.accountCreatedAt().get(), inputs.assessedAt());
        if (age.compareTo(properties.newAccountAge()) < 0) {
            return RiskFinding.fired(
                    RiskSignal.NEW_ACCOUNT, weight, "account is %d hours old".formatted(age.toHours()));
        }
        return RiskFinding.clear(RiskSignal.NEW_ACCOUNT, weight);
    }

    /**
     * A source address this account has never signed in from.
     *
     * <p>The half of "mismatched geography" this platform can answer. Clear rather than
     * fired when the account has no recorded addresses at all: a first session is
     * unfamiliar by definition, and firing on it would make this signal a second, worse
     * copy of {@link RiskSignal#NEW_ACCOUNT}.
     */
    private RiskFinding unfamiliarAddress(RiskInputs inputs) {
        int weight = properties.weights().unfamiliarAddress();

        if (inputs.sourceAddress().isEmpty()) {
            return RiskFinding.unavailable(RiskSignal.UNFAMILIAR_ADDRESS, weight, "no source address");
        }
        if (inputs.knownAddresses().isEmpty()) {
            return RiskFinding.clear(RiskSignal.UNFAMILIAR_ADDRESS, weight);
        }
        if (!inputs.knownAddresses().contains(inputs.sourceAddress().get())) {
            // The address itself is NOT in the detail. §17.4 keeps personal data out of
            // columns read by people who are not the person, and an address is one.
            return RiskFinding.fired(
                    RiskSignal.UNFAMILIAR_ADDRESS,
                    weight,
                    "address not among the %d this account has used".formatted(inputs.knownAddresses().size()));
        }
        return RiskFinding.clear(RiskSignal.UNFAMILIAR_ADDRESS, weight);
    }

    /**
     * §17.2's geography mismatch, which this platform cannot evaluate.
     *
     * <p>{@code AddressGeography} resolves nothing on every deployment, so this is
     * {@link SignalOutcome#UNAVAILABLE} always. The arithmetic below is written out anyway
     * — it is three lines, and writing it the day a geolocation source arrives is writing
     * it in a hurry.
     */
    private RiskFinding geographyMismatch(RiskInputs inputs) {
        int weight = properties.weights().geographyMismatch();

        Optional<String> from = inputs.sourceCountry();
        Optional<String> to = inputs.destinationCountry();
        if (from.isEmpty() || to.isEmpty()) {
            return RiskFinding.unavailable(
                    RiskSignal.GEOGRAPHY_MISMATCH,
                    weight,
                    from.isEmpty() ? "no geolocation source configured" : "the pledge names no destination");
        }
        if (!from.get().equalsIgnoreCase(to.get())) {
            return RiskFinding.fired(
                    RiskSignal.GEOGRAPHY_MISMATCH, weight, "pledged from %s, shipping to %s".formatted(from.get(), to.get()));
        }
        return RiskFinding.clear(RiskSignal.GEOGRAPHY_MISMATCH, weight);
    }

    private RiskDecision decisionFor(int score) {
        // BLOCK is unreachable, deliberately. RiskDecision says why.
        return score >= properties.reviewAtScore() ? RiskDecision.REVIEW : RiskDecision.ALLOW;
    }

    /** A window as a person would say it, for the finding's detail. */
    private static String window(Duration window) {
        return window.toHours() >= 1 ? window.toHours() + "h" : window.toMinutes() + "m";
    }
}
