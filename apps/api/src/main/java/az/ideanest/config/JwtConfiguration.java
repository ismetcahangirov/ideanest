package az.ideanest.config;

import az.ideanest.auth.AuthProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing and verifying the access token.
 *
 * <p>RS256 rather than a shared secret. With HMAC, every party that verifies a
 * token can also mint one; the moment a second service needs to check a token,
 * the secret has to be given to it and the blast radius of that service being
 * compromised becomes "can impersonate anybody". With a key pair, verifiers get
 * the public half and can only ever verify.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

    /** Where a generated key is acceptable, and nowhere else. */
    private static final Set<String> EPHEMERAL_KEY_PROFILES = Set.of("local", "test");

    private static final int GENERATED_KEY_SIZE = 2048;

    @Bean
    public RSAKey accessTokenSigningKey(AuthProperties properties, Environment environment) {
        AuthProperties.Token token = properties.token();

        if (hasText(token.privateKeyPem()) && hasText(token.publicKeyPem())) {
            return new RSAKey.Builder(readPublicKey(token.publicKeyPem()))
                    .privateKey(readPrivateKey(token.privateKeyPem()))
                    .keyID("ideanest-access")
                    .build();
        }

        boolean ephemeralAllowed =
                List.of(environment.getActiveProfiles()).stream().anyMatch(EPHEMERAL_KEY_PROFILES::contains)
                        || List.of(environment.getDefaultProfiles()).stream().anyMatch(EPHEMERAL_KEY_PROFILES::contains);

        if (!ephemeralAllowed) {
            // Generating one here would work, and would sign tokens with a key
            // that changes on every restart and differs between replicas —
            // signing everybody out at random and being impossible to diagnose.
            throw new IllegalStateException(
                    "No signing key configured. Set ideanest.auth.token.private-key-pem and public-key-pem"
                            + " from the secret store. A key is not generated outside local and test.");
        }

        log.warn("No signing key configured; generating an ephemeral one. Tokens will not survive a restart.");
        KeyPair keyPair = generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("ideanest-access-ephemeral")
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(new JWKSet(signingKey));
        return new NimbusJwtEncoder(keys);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties properties) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                    // Pinned. Without it, a decoder verifies whatever algorithm
                    // the token's own header names — which is how "alg: none"
                    // became a family of authentication bypasses.
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("The signing key cannot be used for verification", e);
        }

        AuthProperties.Token token = properties.token();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // Expiry, and an issuer that must be ours. A token minted for
                // staging must not open production.
                JwtValidators.createDefaultWithIssuer(token.issuer()),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD,
                        // A token issued for another audience is a token issued
                        // for somebody else's service, whoever signed it.
                        audiences -> audiences != null && audiences.contains(token.audience()))));
        return decoder;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static RSAPublicKey readPublicKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("ideanest.auth.token.public-key-pem is not an RSA public key", e);
        }
    }

    private static RSAPrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // No key material in the message. This exception reaches a log.
            throw new IllegalStateException("ideanest.auth.token.private-key-pem is not a PKCS#8 RSA private key", e);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String base64 = pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is unavailable", e);
        }
    }
}
