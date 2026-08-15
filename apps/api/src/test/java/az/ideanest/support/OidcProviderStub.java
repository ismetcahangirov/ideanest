package az.ideanest.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google and Apple, as far as an ID token is concerned.
 *
 * <p>Neither provider is called from a test. A suite that reaches the internet
 * fails for reasons that are not ours, and — more to the point — neither Google
 * nor Apple will sign a token for an account we invented, so the interesting
 * cases (a wrong audience, an expired token, a token signed by the wrong key)
 * are unreachable with a real provider. What is real here is everything that
 * matters: a JWKS served over HTTP, and tokens signed with a key pair this class
 * generates.
 *
 * <p>One server for the whole suite, on a port the operating system picks. A
 * fixed port is a test that fails on whichever machine already has something
 * listening there.
 */
public final class OidcProviderStub {

    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    public static final String APPLE_ISSUER = "https://appleid.apple.com";

    /** The client identifiers {@code application-test.yml} accepts. */
    public static final String GOOGLE_AUDIENCE = "ideanest-test.apps.googleusercontent.com";

    public static final String APPLE_AUDIENCE = "az.ideanest.test";

    private static final String GOOGLE_JWKS_PATH = "/google/jwks";
    private static final String APPLE_JWKS_PATH = "/apple/jwks";

    /**
     * The published key and an unpublished one with the <em>same</em> key
     * identifier.
     *
     * <p>Sharing the identifier is the point: a token signed with the impostor
     * key selects the right key from the key set and then fails on the
     * signature, which is the case worth testing. Two different identifiers
     * would fail earlier, at key selection, and would not prove that the
     * signature is checked at all.
     */
    private static final String KEY_ID = "provider-signing-key";

    private static final WireMockServer SERVER;
    private static final RSAKey SIGNING_KEY;
    private static final RSAKey IMPOSTOR_KEY;

    static {
        SIGNING_KEY = generateKey();
        IMPOSTOR_KEY = generateKey();

        SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        SERVER.start();

        String jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
        SERVER.stubFor(WireMock.get(WireMock.urlEqualTo(GOOGLE_JWKS_PATH)).willReturn(WireMock.okJson(jwks)));
        SERVER.stubFor(WireMock.get(WireMock.urlEqualTo(APPLE_JWKS_PATH)).willReturn(WireMock.okJson(jwks)));

        // Gradle's test worker exits when the tests are done, and Jetty's
        // threads are not daemons.
        Runtime.getRuntime().addShutdownHook(new Thread(SERVER::stop, "oidc-provider-stub-shutdown"));
    }

    private OidcProviderStub() {
    }

    public static String googleJwksUri() {
        return SERVER.baseUrl() + GOOGLE_JWKS_PATH;
    }

    public static String appleJwksUri() {
        return SERVER.baseUrl() + APPLE_JWKS_PATH;
    }

    /** A token shaped like Google's: a name in the claims, and a real address. */
    public static Builder googleToken(String subject) {
        return new Builder(GOOGLE_ISSUER, GOOGLE_AUDIENCE, subject);
    }

    /**
     * A token shaped like Apple's. Apple sends <strong>no name</strong> in the
     * token — it appears once, in the body of the first authorisation response —
     * and its address may be a relay.
     */
    public static Builder appleToken(String subject) {
        return new Builder(APPLE_ISSUER, APPLE_AUDIENCE, subject);
    }

    /** The claims of an ID token, and the three ways to serialise one. */
    public static final class Builder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private String issuer;
        private String audience;
        private final String subject;
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = Instant.now().plusSeconds(3600);

        private Builder(String issuer, String audience, String subject) {
            this.issuer = issuer;
            this.audience = audience;
            this.subject = subject;
        }

        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        public Builder email(String value) {
            claims.put("email", value);
            return this;
        }

        /**
         * Deliberately {@link Object}: both providers have sent this as the
         * string {@code "true"} rather than as a boolean, and a verifier that
         * only handles one of the two reads the other as {@code false} — which
         * is the difference between linking an account and refusing to.
         */
        public Builder emailVerified(Object value) {
            claims.put("email_verified", value);
            return this;
        }

        public Builder privateEmail(Object value) {
            claims.put("is_private_email", value);
            return this;
        }

        public Builder name(String value) {
            claims.put("name", value);
            return this;
        }

        public Builder nonce(String value) {
            claims.put("nonce", value);
            return this;
        }

        public Builder issuedAt(Instant value) {
            this.issuedAt = value;
            return this;
        }

        public Builder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        /** Signed with the key the stub publishes. The happy path. */
        public String sign() {
            return sign(SIGNING_KEY);
        }

        /**
         * Signed with a key that is not in the key set, under the identifier of
         * one that is. A verifier that fetches the key set and then does not
         * check the signature accepts this.
         */
        public String signWithWrongKey() {
            return sign(IMPOSTOR_KEY);
        }

        /**
         * {@code alg: none} — a header, a body, and an empty signature. The
         * oldest JWT bypass there is, and it works against any library that
         * takes the algorithm from the token instead of from configuration.
         */
        public String unsigned() {
            return new PlainJWT(claimsSet()).serialize();
        }

        private String sign(RSAKey key) {
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .keyID(KEY_ID)
                                .build(),
                        claimsSet());
                jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("could not sign a test ID token", e);
            }
        }

        private JWTClaimsSet claimsSet() {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt));
            claims.forEach(builder::claim);
            return builder.build();
        }
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is unavailable", e);
        }
    }
}
