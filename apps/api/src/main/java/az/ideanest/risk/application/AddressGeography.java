package az.ideanest.risk.application;

import java.util.Optional;

/**
 * Which country an address is in — §17.2's geography mismatch, issue #108.
 *
 * <p><strong>There is one implementation and it resolves nothing.</strong> Answering this
 * needs an IP-to-country database: a commercial feed, or a downloaded file with its own
 * licence, update schedule and deployment story. No vendor is chosen, nothing ships with
 * the service, and inventing one would be a lookup table this repository made up — which
 * is worse than an absence, because it would look like coverage.
 *
 * <p>The port exists so that the signal is <em>named and reported unavailable</em> rather
 * than quietly missing. See {@code SignalOutcome}: an assessment that scores low with this
 * signal unavailable is telling the truth about what it knows, and one that omitted the
 * signal would not be.
 *
 * <p>An implementation arrives as a {@code @Component} of this type and nothing else
 * changes: {@code RiskFacts} asks, {@code RiskScorer} already has the comparison written.
 */
public interface AddressGeography {

    /**
     * The ISO 3166-1 alpha-2 country for an address, or empty when it cannot be resolved.
     *
     * <p>Empty covers both "no source is configured" and "the source has no answer for
     * this address", and the caller treats them the same — both mean the signal cannot be
     * evaluated. An implementation must not throw: a geolocation lookup that failed is not
     * a reason to lose the four signals that did work.
     */
    Optional<String> countryOf(String address);
}
