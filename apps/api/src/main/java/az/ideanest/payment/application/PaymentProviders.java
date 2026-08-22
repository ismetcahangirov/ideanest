package az.ideanest.payment.application;

import az.ideanest.payment.PaymentProperties;
import az.ideanest.payment.domain.PaymentProvider;
import az.ideanest.payment.domain.ProviderCapabilities;
import az.ideanest.payment.domain.ProviderName;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every adapter on the classpath, checked against §9.3 and indexed by name (#61).
 *
 * <p>Three jobs, and each of them is a failure this class exists to prevent.
 *
 * <ol>
 *   <li><strong>It refuses an adapter that cannot do R-01, R-02 and R-03, at
 *       start-up.</strong> {@link ProviderCapabilities} argues why the check is here
 *       rather than at the first charge: the first charge is at a campaign's close, in
 *       front of every backer who has just been told the campaign succeeded.
 *   <li><strong>It resolves the configured primary once.</strong> A deployment naming a
 *       provider with no adapter is a start-up failure, because a deployment that thinks
 *       it can collect and cannot is discovered on exactly the wrong day.
 *   <li><strong>It answers "is there a provider at all".</strong> There is not — see
 *       below — and {@code CollectionRun} refusing on that answer is the single gate
 *       that keeps every piece of collection machinery inert until #60 is answered.
 * </ol>
 *
 * <h2>The registry is empty in every deployed environment today</h2>
 *
 * <p>{@link PaymentProvider} has no implementations: #60 has not chosen a provider, and
 * §9.2 is explicit that a stub returning approvals "would be worse than nothing".
 * Spring therefore injects an empty list here, {@link #primary()} is empty, and every
 * collection pass logs once and does nothing. That is the correct behaviour and not a
 * degraded one — the alternative to collecting nothing is not collecting something, it
 * is charging cards through an adapter nobody has written.
 *
 * <p>Tests register their own {@link PaymentProvider} bean, which is how the whole of
 * #64, #65 and #66 is exercised without a provider being chosen.
 */
@Component
public class PaymentProviders {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviders.class);

    private final Map<ProviderName, PaymentProvider> adapters = new EnumMap<>(ProviderName.class);
    private final PaymentProvider primary;

    public PaymentProviders(List<PaymentProvider> discovered, PaymentProperties properties) {
        for (PaymentProvider adapter : discovered) {
            ProviderCapabilities capabilities = adapter.capabilities();
            if (!capabilities.supportsStoredCardCollection()) {
                // Refused, not warned about. §9.1 establishes that without these three the
                // model collapses: the platform would hold a payment obligation for sixty
                // days and then find it cannot collect. A service that will not start is a
                // deployment somebody fixes; a warning is a line in a log nobody reads.
                throw new IllegalStateException(
                        "Provider adapter %s cannot do %s, which §9.3 requires before it can be used"
                                .formatted(adapter.name(), String.join(", ", capabilities.missing())));
            }
            PaymentProvider clash = adapters.put(adapter.name(), adapter);
            if (clash != null) {
                // Two adapters for one provider is not a merge to resolve: `provider` is
                // half of two uniqueness rules, and the platform would be charging through
                // whichever bean Spring happened to order first.
                throw new IllegalStateException("Two adapters claim to be " + adapter.name());
            }
        }

        this.primary = resolvePrimary(properties);

        if (primary == null) {
            log.info(
                    "No payment provider is configured; nothing will be collected. "
                            + "#60 chooses one and §9.2 says why no stub ships in the meantime.");
        } else {
            log.info("Collecting through {}.", primary.name());
        }
    }

    /**
     * The adapter the platform charges through, or empty when there is none.
     *
     * <p>Empty is the shipped state. Every caller treats it as "do not collect", never as
     * "collect through something else".
     */
    public Optional<PaymentProvider> primary() {
        return Optional.ofNullable(primary);
    }

    /**
     * The adapter for a named provider, whichever one it is.
     *
     * <p>Not the same question as {@link #primary()}, and the difference matters for
     * #66's webhooks: a delivery arrives at {@code /v1/webhooks/psp/{provider}} naming
     * the provider that sent it, and after a provider change the platform still has to
     * verify and process deliveries about charges made through the old one. Answering
     * those from {@code primary()} would verify a Payriff signature with Epoint's key.
     */
    public Optional<PaymentProvider> byName(ProviderName name) {
        return Optional.ofNullable(adapters.get(name));
    }

    /** Which providers have an adapter. For the health endpoint and for start-up logging. */
    public Set<ProviderName> registered() {
        return Set.copyOf(adapters.keySet());
    }

    private PaymentProvider resolvePrimary(PaymentProperties properties) {
        if (!properties.provider().isConfigured()) {
            return null;
        }
        // Throws UnknownProviderException on a name that is not in §9.3's list, and the
        // IllegalStateException below on a name that is but has no adapter. Both are
        // start-up failures: see the class comment.
        ProviderName name = ProviderName.of(properties.provider().primary());
        PaymentProvider adapter = adapters.get(name);
        if (adapter == null) {
            throw new IllegalStateException(
                    "ideanest.payment.provider.primary names %s, and no adapter for it is on the classpath"
                            .formatted(name));
        }
        return adapter;
    }
}
