package az.ideanest.risk.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Every fact {@link RiskScorer} is allowed to use — issue #108.
 *
 * <p><strong>The scorer takes this and nothing else</strong>, which is what makes it pure
 * and therefore testable. Gathering these is {@code RiskFacts}' job and involves reading
 * four tables; deciding what they mean is one class with no dependencies.
 *
 * <p>Everything that can be absent is an {@link Optional} rather than a null or a sentinel,
 * because absence is a real outcome here: a signal whose input is missing is reported as
 * unavailable rather than as clear, and a null that defaulted to zero would be the exact
 * lie {@code SignalOutcome} exists to prevent.
 *
 * @param assessedAt the moment being judged. On the event rather than from a clock, so an
 *     assessment that ran an hour late judges the pledge against the hour it happened
 * @param sourceAddress where the pledge came from, when a request is behind it
 * @param accountCreatedAt when the backer registered
 * @param knownAddresses every address this account has held a session from, before this
 *     pledge. Empty for an account whose sessions have all expired, which is why an empty
 *     set is treated as "no opinion" rather than as "this one is new"
 * @param recentPledgesByAccount other pledges by this account inside the velocity window
 * @param recentPledgesByAddress pledges from this address inside the same window, across
 *     every account
 * @param sourceCountry the country of {@code sourceAddress}, when a geolocation source is
 *     configured. Empty on every deployment today
 * @param destinationCountry where the reward is going, when the pledge names a destination
 */
public record RiskInputs(
        Instant assessedAt,
        Optional<String> sourceAddress,
        Optional<Instant> accountCreatedAt,
        Set<String> knownAddresses,
        int recentPledgesByAccount,
        int recentPledgesByAddress,
        Optional<String> sourceCountry,
        Optional<String> destinationCountry) {}
