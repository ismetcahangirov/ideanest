package az.ideanest.risk.infrastructure;

import az.ideanest.risk.application.AddressGeography;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The geolocation source this platform does not have — issue #108.
 *
 * <h2>The name is the point</h2>
 *
 * <p>There is no {@code MaxMindAddressGeography} and no {@code Ip2LocationAddressGeography}
 * in this change, for the reason {@code UndeliverableChannelSender} gives about transports:
 * a class named after a source that resolves nothing is a component every reader assumes
 * works, and the first sign otherwise is a geography signal that has silently been clearing
 * for a year. One class, named for what it actually is, is the version that cannot be
 * misread.
 *
 * <h2>Why nothing was built instead</h2>
 *
 * <p>An IP-to-country answer needs a database: a commercial feed with a licence and a
 * monthly update, or a downloaded file with the same and a deployment story for keeping it
 * current. No vendor is chosen. Writing a lookup table into this repository would be
 * inventing the data, and a signal built on invented data is worse than an absent one
 * because it looks like coverage.
 *
 * <p>What this arrangement buys is that the signal is <em>named and reported
 * unavailable</em> on every assessment, so a queue showing a low score also shows that one
 * of the five signals could not be evaluated. See {@code SignalOutcome} on why that is a
 * different statement from a signal that found nothing.
 *
 * <h2>Replacing it</h2>
 *
 * <p>A real implementation arrives as a {@code @Component} of type {@link AddressGeography}
 * and this one stands aside — {@code RiskConfiguration} registers this as
 * {@code @ConditionalOnMissingBean}, which is the whole of the wiring. {@code RiskScorer}
 * already carries the comparison; nothing else changes.
 *
 * <p>It is registered from a {@code @Configuration} rather than annotated
 * {@code @Component}, and that is not a style choice: {@code @ConditionalOnMissingBean} on
 * a scanned component is evaluated before the scan has found the bean it is asking about,
 * so it either always matches or never does depending on ordering. Spring's own
 * documentation warns against it, and the failure here was the honest one — the context
 * refused to start with no {@link AddressGeography} at all.
 */
public class UnresolvedAddressGeography implements AddressGeography {

    private static final Logger log = LoggerFactory.getLogger(UnresolvedAddressGeography.class);

    /**
     * Once, at start-up, at {@code INFO}.
     *
     * <p>Not per lookup: that would be a line per pledge for a missing feature, which is how
     * a log becomes unreadable. Not silence either — a deployment that believes it is
     * checking geography and is not should be told, in the same place the cache invalidator
     * and the metrics endpoint say the same kind of thing.
     */
    @PostConstruct
    void sayItIsNotConfigured() {
        log.info("Geography risk signals are unavailable: no IP geolocation source is configured (#108).");
    }

    @Override
    public Optional<String> countryOf(String address) {
        return Optional.empty();
    }
}
