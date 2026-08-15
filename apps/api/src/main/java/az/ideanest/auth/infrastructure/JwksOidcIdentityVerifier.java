package az.ideanest.auth.infrastructure;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AuthenticationFailedException;
import az.ideanest.auth.application.OidcIdentityVerifier;
import az.ideanest.auth.application.ProviderNotConfiguredException;
import az.ideanest.auth.domain.IdentityProvider;
import az.ideanest.shared.EmailAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies a provider ID token against the keys that provider publishes.
 *
 * <p>Every check here exists because skipping it is a documented way to sign in
 * as somebody else.
 *
 * <ul>
 *   <li><strong>Signature, against the provider's JWKS.</strong> Fetched from
 *       the configured URI, never from the token's own {@code jku} header, which
 *       is a suggestion from whoever made the token.
 *   <li><strong>Algorithm, pinned to RS256.</strong> A decoder that honours the
 *       algorithm named in the token accepts {@code alg: none} — the oldest JWT
 *       bypass there is — and, given an RSA public key, accepts an HMAC token
 *       signed with that public key as its secret.
 *   <li><strong>{@code iss}.</strong> A correctly signed token from a different
 *       issuer is a different person's account.
 *   <li><strong>{@code aud}.</strong> The check that matters most and the one
 *       most often left out. Google issues valid, correctly signed tokens to
 *       every developer who asks; what makes one ours is that it names our
 *       client identifier. Without this check, any app on the internet can hand
 *       us its users' tokens and sign in as them.
 *   <li><strong>{@code exp}, and how old the token is.</strong> Expiry is the
 *       provider's limit — an hour at Google. The age limit is ours, because a
 *       sign-in that just happened produces a token seconds old and anything
 *       older has been sitting somewhere.
 *   <li><strong>{@code nonce}.</strong> Binds the token to the authorisation
 *       request the client made.
 * </ul>
 *
 * <p>A provider absent from configuration gets no decoder and no key fetch. One
 * configured in part stops start-up: an issuer with no audience is not something
 * anybody decided, and it would otherwise surface as a 401 nobody can explain.
 */
@Component
public class JwksOidcIdentityVerifier implements OidcIdentityVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwksOidcIdentityVerifier.class);

    /**
     * One refusal for every way a token can be wrong. Which check failed is a
     * detail for our logs; to the caller it is the same "sign in again".
     */
    private static final String REFUSAL = "That sign-in could not be verified. Try again.";

    private final Map<IdentityProvider, ConfiguredProvider> providers;
    private final Duration maxTokenAge;
    private final Clock clock;

    public JwksOidcIdentityVerifier(AuthProperties properties, Clock clock) {
        this.clock = clock;
        AuthProperties.OAuth oauth = properties.oauth();
        this.maxTokenAge = oauth.maxTokenAge();
        this.providers = build(oauth);
    }

    private static Map<IdentityProvider, ConfiguredProvider> build(AuthProperties.OAuth oauth) {
        Map<IdentityProvider, ConfiguredProvider> built = new EnumMap<>(IdentityProvider.class);
        Map<IdentityProvider, AuthProperties.OAuth.Provider> configured =
                oauth.providers() == null ? Map.of() : oauth.providers();

        for (Map.Entry<IdentityProvider, AuthProperties.OAuth.Provider> entry : configured.entrySet()) {
            AuthProperties.OAuth.Provider settings = entry.getValue();
            if (settings == null || (!settings.isConfigured() && !settings.isPartiallyConfigured())) {
                continue;
            }
            if (settings.isPartiallyConfigured()) {
                // Half a configuration authenticates nobody, and the failure it
                // produces is a 401 that looks like the user's fault.
                throw new IllegalStateException("ideanest.auth.oauth.providers." + entry.getKey().key()
                        + " needs an issuer, a jwks-uri, and at least one audience. Configure it fully or remove it.");
            }
            built.put(entry.getKey(), configure(settings, oauth.clockSkew()));
        }

        log.info(
                "Social sign-in enabled for {}",
                built.isEmpty() ? "no providers" : built.keySet());
        return built;
    }

    private static ConfiguredProvider configure(AuthProperties.OAuth.Provider settings, Duration clockSkew) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwksUri())
                // Pinned rather than read from the token's header. Both
                // providers sign with RS256; a token announcing anything else is
                // not one of theirs, whatever it claims.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        // A trailing comma in an environment variable produces a blank entry,
        // and a blank audience would match a token with a blank aud.
        List<String> audiences = settings.audiences().stream()
                .filter(audience -> audience != null && !audience.isBlank())
                .map(String::trim)
                .toList();
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(clockSkew),
                new JwtIssuerValidator(settings.issuer()),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, claimed -> claimed != null && claimed.stream().anyMatch(audiences::contains)),
                // A token with no subject identifies nobody, and the account is
                // keyed on the subject.
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()));
        decoder.setJwtValidator(validators);

        return new ConfiguredProvider(decoder, settings.requireNonce());
    }

    @Override
    public VerifiedIdentity verify(IdentityProvider provider, String idToken, String presentedNonce) {
        ConfiguredProvider configured = providers.get(provider);
        if (configured == null) {
            throw new ProviderNotConfiguredException(provider);
        }

        Jwt token;
        try {
            token = configured.decoder().decode(idToken);
        } catch (JwtException e) {
            // The reason is worth having in our logs and worth nothing to the
            // caller. No token, and no claims: this line is read by more people
            // than the database is.
            log.info("Rejected a {} ID token: {}", provider.key(), e.getMessage());
            throw new AuthenticationFailedException(REFUSAL);
        }

        checkAge(provider, token);
        checkNonce(provider, token, presentedNonce, configured.requireNonce());

        return new VerifiedIdentity(
                provider,
                token.getSubject(),
                emailOf(token),
                booleanClaim(token, "email_verified"),
                booleanClaim(token, "is_private_email"),
                nameOf(token));
    }

    /**
     * How long ago the provider minted this token.
     *
     * <p>Expiry is the provider's policy and it is generous: an hour at Google.
     * A token that is genuinely the result of the sign-in happening right now is
     * seconds old, so anything much older has been stored somewhere — a log, a
     * crash report, a proxy — and a stored bearer artefact is one somebody else
     * may be holding.
     */
    private void checkAge(IdentityProvider provider, Jwt token) {
        Instant issuedAt = token.getIssuedAt();
        if (issuedAt == null) {
            log.info("Rejected a {} ID token with no iat claim", provider.key());
            throw new AuthenticationFailedException(REFUSAL);
        }
        if (issuedAt.plus(maxTokenAge).isBefore(clock.instant())) {
            log.info("Rejected a {} ID token older than {}", provider.key(), maxTokenAge);
            throw new AuthenticationFailedException(REFUSAL);
        }
    }

    /**
     * The nonce, which ties the token to the authorisation request that produced
     * it.
     *
     * <p>What this proves and what it does not is worth being precise about.
     * With the nonce supplied by the client rather than issued and remembered by
     * us, a match proves the token and the request agree; it does not prove
     * freshness, because whoever holds a stolen token also holds the nonce
     * inside it. What it does defeat is the token being replayed into a
     * <em>different</em> client session. Server-issued nonces need storage
     * shared across replicas and arrive with #134; until then the age limit
     * above is the freshness control, and this is the binding one.
     *
     * <p>Apple's native flow hashes the nonce before it reaches the token, so
     * the value compared here is whatever the client put in its authorisation
     * request — the SHA-256 on iOS, the raw string on the web. We compare, we do
     * not interpret.
     */
    private void checkNonce(IdentityProvider provider, Jwt token, String presentedNonce, boolean required) {
        String claimed = token.getClaimAsString("nonce");

        if (required && (claimed == null || presentedNonce == null)) {
            log.info("Rejected a {} ID token without the required nonce", provider.key());
            throw new AuthenticationFailedException(REFUSAL);
        }
        if (claimed == null && presentedNonce == null) {
            return;
        }
        if (claimed == null || presentedNonce == null || !constantTimeEquals(claimed, presentedNonce)) {
            // A token bound to a nonce the caller did not send is a token
            // obtained for some other session.
            log.info("Rejected a {} ID token whose nonce did not match the request", provider.key());
            throw new AuthenticationFailedException(REFUSAL);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The address, if the provider sent a usable one.
     *
     * <p>An unparseable address is treated as no address rather than as a
     * failure of the token: the signature was valid, the person is who they say
     * they are, and what is missing is something we need in order to open an
     * account. The caller turns that into its own refusal.
     */
    private static EmailAddress emailOf(Jwt token) {
        String raw = token.getClaimAsString("email");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EmailAddress.of(raw);
        } catch (IllegalArgumentException e) {
            log.info("A provider asserted an address that is not one");
            return null;
        }
    }

    /**
     * A claim that is a boolean, or the string spelling of one.
     *
     * <p>Not defensiveness for its own sake: Apple sends {@code email_verified}
     * as {@code "true"} and Google has sent both over the years. Reading it with
     * a cast produces a {@code ClassCastException} in one case and, worse, a
     * silent {@code false} in the other — and {@code false} here is the
     * difference between linking an account and refusing to.
     */
    private static boolean booleanClaim(Jwt token, String name) {
        Object value = token.getClaims().get(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text && "true".equalsIgnoreCase(text.trim());
    }

    /**
     * A display name, if the provider supplied one.
     *
     * <p>Apple never does in the ID token. It sends the name once, in the body
     * of the first authorisation response, and never again; the client has to
     * pass it on at that moment or it is gone.
     */
    private static String nameOf(Jwt token) {
        String name = token.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String given = token.getClaimAsString("given_name");
        String family = token.getClaimAsString("family_name");
        String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        return joined.isEmpty() ? null : joined;
    }

    private record ConfiguredProvider(JwtDecoder decoder, boolean requireNonce) {
    }
}
