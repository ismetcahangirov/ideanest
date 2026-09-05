package az.ideanest.signature;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signature settings: which provider, where it lives, and how long a citizen has — #428.
 *
 * @param provider which adapter the platform signs through. See {@link Provider}
 * @param sima SİMA İmza's own settings, ignored unless it is the configured provider
 */
@ConfigurationProperties(prefix = "ideanest.signature")
public record SignatureProperties(Provider provider, Sima sima) {

    public SignatureProperties {
        // A deployment that configures none of this still starts, for PaymentProperties'
        // reason: a nested record binds to null when its whole block is absent, and a null
        // here would be a NullPointerException at the first signature rather than a
        // configuration error at start-up.
        provider = provider == null ? Provider.defaults() : provider;
        sima = sima == null ? Sima.defaults() : sima;
    }

    /**
     * Which provider the platform signs through.
     *
     * @param primary the {@code SignatureProviderName} of the adapter to use, or blank for
     *     none. <strong>Blank is the shipped default and the deployed reality</strong>: #428
     *     builds the mechanism, #429 is the caller, and neither may be pointed at production
     *     SİMA until #423 answers what personal data may be kept and for how long.
     *     <p>A value naming a provider with no adapter on the classpath is a start-up failure
     *     and not a warning — {@code PaymentProperties.Provider} makes the argument and it
     *     transfers exactly: a deployment that thinks it can take a legally binding signature
     *     and cannot is a configuration mistake discovered on the day somebody disputes one.
     */
    public record Provider(String primary) {

        public static Provider defaults() {
            return new Provider("");
        }

        public Provider {
            primary = primary == null ? "" : primary.trim();
        }

        /** Whether a deployment has named a provider at all. */
        public boolean isConfigured() {
            return !primary.isBlank();
        }
    }

    /**
     * SİMA İmza's endpoint and the shape of a session.
     *
     * @param environment which SİMA this points at. <strong>{@link SimaEnvironment#PRODUCTION}
     *     is refused at start-up</strong> until #423 answers — {@code SignatureProviders} is
     *     where that refusal lives, and the reason it is a refusal rather than a warning is
     *     that the thing being protected is a citizen's name and FIN
     * @param baseUrl where the sandbox lives. Blank by default, because there is no sensible
     *     default for somebody else's service and a guessed one is a deployment that appears
     *     configured
     * @param clientId the credential's identifier, held the way provider credentials are held
     *     — in the environment, never in this repository
     * @param clientSecret likewise. Never logged; {@code SimaImzaSignatureProvider} redacts
     *     before anything reaches a log line
     * @param sessionLife how long a citizen has to answer the prompt before SİMA closes the
     *     session. Five minutes: long enough to find a phone, short enough that a prompt
     *     somebody has forgotten about is not still signable an hour later
     * @param requestTimeout how long the platform waits on one HTTP call before treating SİMA
     *     as unreachable. Deliberately short — a request thread blocked on a third party is
     *     the failure mode that takes the whole service down with it
     */
    public record Sima(
            SimaEnvironment environment,
            String baseUrl,
            String clientId,
            String clientSecret,
            Duration sessionLife,
            Duration requestTimeout) {

        private static final Duration DEFAULT_SESSION_LIFE = Duration.ofMinutes(5);
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

        public static Sima defaults() {
            return new Sima(SimaEnvironment.SANDBOX, "", "", "", DEFAULT_SESSION_LIFE, DEFAULT_REQUEST_TIMEOUT);
        }

        public Sima {
            // SANDBOX rather than null, so that a deployment which sets a base URL and forgets
            // the environment points at the safe one. The unsafe value has to be typed.
            environment = environment == null ? SimaEnvironment.SANDBOX : environment;
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            clientId = clientId == null ? "" : clientId.trim();
            clientSecret = clientSecret == null ? "" : clientSecret.trim();
            sessionLife = sessionLife == null ? DEFAULT_SESSION_LIFE : sessionLife;
            requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        }

        /** Whether there is enough here to talk to anything. */
        public boolean isComplete() {
            return !baseUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    /**
     * Which SİMA a deployment is pointed at — #428, and the gate #423 owns.
     *
     * <p>Two values and not a boolean, because a boolean named {@code production} reads as a
     * feature switch and this is a statement about whose data is on the other end. The sandbox
     * issues test certificates against invented citizens; production issues them against real
     * ones, whose name and FIN are §17.4 personal data the platform has not yet been told it
     * may keep.
     */
    public enum SimaEnvironment {

        /** SİMA's test environment. The only value this repository may run against today. */
        SANDBOX,

        /**
         * The real one.
         *
         * <p><strong>Refused at start-up.</strong> #428's definition of done says "no
         * production credentials, and an explicit note on the issue that #423 gates that", and
         * a note is not a control. {@code SignatureProviders} refuses to start on this value,
         * so the gate is a deployment that will not come up rather than a paragraph somebody
         * read once.
         *
         * <p>Lifting it is a one-line change in {@code SignatureProviders}, made by whoever
         * closes #423 and having read the personal-data row they obtained. That is the friction
         * it exists to create.
         */
        PRODUCTION
    }
}
