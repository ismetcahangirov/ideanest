package az.ideanest.signature.application;

import az.ideanest.signature.SignatureProperties;
import az.ideanest.signature.domain.SignatureProvider;
import az.ideanest.signature.domain.SignatureProviderName;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every signature adapter on the classpath, resolved against the configuration — #428.
 *
 * <p>{@code PaymentProviders}' shape, and three jobs that are each a failure this class exists
 * to prevent.
 *
 * <ol>
 *   <li><strong>It refuses to start against production SİMA.</strong> #423 has not answered
 *       what personal data may be kept and for how long, and until it does the only thing on
 *       the other end of this integration may be invented citizens. A refusal is a deployment
 *       somebody fixes; a warning is a line in a log nobody reads, and what is at stake is a
 *       real person's name and FIN.
 *   <li><strong>It resolves the configured provider once, at start-up.</strong> A deployment
 *       naming a provider with no adapter is a start-up failure, because a deployment that
 *       thinks it can take a legally binding signature and cannot is discovered on the day
 *       somebody disputes one.
 *   <li><strong>It answers "is there a provider at all".</strong> There is not, in any deployed
 *       environment today — the property is blank by default — and #429's caller refusing on
 *       that answer is what keeps the signing path inert rather than half-working.
 * </ol>
 *
 * <h2>Why an adapter ships here where none ships for payments</h2>
 *
 * <p>{@code PaymentProviders} finds nothing because §9.2 argues a stub "would be worse than
 * nothing: it would make this path look finished and would have told clients that cards were
 * verified when no card was ever seen". That argument is about a <em>fake</em>, and it holds.
 *
 * <p>{@code SimaImzaSignatureProvider} is not a fake. It speaks to SİMA's real sandbox, which
 * issues real signatures against test certificates, and it is switched off by configuration
 * rather than by absence. The risk §9.2 was guarding against — a path that looks finished — is
 * answered by the blank default and by the production refusal above, not by leaving the module
 * empty.
 */
@Component
public class SignatureProviders {

    private static final Logger log = LoggerFactory.getLogger(SignatureProviders.class);

    private final Map<SignatureProviderName, SignatureProvider> adapters =
            new EnumMap<>(SignatureProviderName.class);
    private final SignatureProvider primary;

    public SignatureProviders(List<SignatureProvider> discovered, SignatureProperties properties) {
        for (SignatureProvider adapter : discovered) {
            SignatureProvider clash = adapters.put(adapter.name(), adapter);
            if (clash != null) {
                // Two adapters for one provider is not a merge to resolve: `provider` is half
                // of V67's uniqueness rule, and the platform would be signing through whichever
                // bean Spring happened to order first.
                throw new IllegalStateException("Two adapters claim to be " + adapter.name());
            }
        }

        this.primary = resolvePrimary(properties);

        if (primary == null) {
            log.info(
                    "No signature provider is configured; nothing will be signed. "
                            + "#429 is the caller and #423 gates production SİMA.");
        } else {
            log.info("Signing through {} against {}.", primary.name(), properties.sima().environment());
        }
    }

    /**
     * The adapter the platform signs through, or empty when there is none.
     *
     * <p>Empty is the shipped state. A caller treats it as "cannot take a signature", never as
     * "take an acceptance instead" — that substitution is #429's to make explicitly, if it
     * makes it at all, and making it here would turn a configuration gap into a silently
     * weaker legal position.
     */
    public Optional<SignatureProvider> primary() {
        return Optional.ofNullable(primary);
    }

    /**
     * The adapter for a named provider, whichever one it is.
     *
     * <p>Not the same question as {@link #primary()}. After a provider change the platform
     * still has to verify signatures taken through the old one — V67 stores the provider on
     * every row for exactly that reason — and answering those from {@code primary()} would
     * check a SİMA signature with ASAN's keys.
     */
    public Optional<SignatureProvider> byName(SignatureProviderName name) {
        return Optional.ofNullable(adapters.get(name));
    }

    /** Which providers have an adapter. For the health endpoint and for start-up logging. */
    public Set<SignatureProviderName> registered() {
        return Set.copyOf(adapters.keySet());
    }

    private SignatureProvider resolvePrimary(SignatureProperties properties) {
        if (!properties.provider().isConfigured()) {
            return null;
        }
        if (properties.sima().environment() == SignatureProperties.SimaEnvironment.PRODUCTION) {
            // The gate #423 owns, as a refusal rather than a note. See the class comment.
            throw new IllegalStateException(
                    "ideanest.signature.sima.environment is PRODUCTION. #423 has not answered which"
                            + " personal data from a SİMA certificate may be kept and for how long, and"
                            + " until it has, this integration runs against the sandbox only.");
        }
        // Throws IllegalArgumentException on a name that is not a provider, and the
        // IllegalStateException below on a name that is but has no adapter. Both are start-up
        // failures: see the class comment.
        SignatureProviderName name = SignatureProviderName.of(properties.provider().primary());
        SignatureProvider adapter = adapters.get(name);
        if (adapter == null) {
            throw new IllegalStateException(
                    "ideanest.signature.provider.primary names %s, and no adapter for it is on the classpath"
                            .formatted(name));
        }
        return adapter;
    }
}
