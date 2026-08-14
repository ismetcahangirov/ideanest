package az.ideanest.auth.infrastructure;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AccessTokenIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** The access token, as a signed JWT. */
@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties.Token settings;

    public JwtAccessTokenIssuer(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.settings = properties.token();
    }

    /**
     * The values {@code amr} carries, from the OpenID Connect registry.
     *
     * <p>A list rather than a boolean because that is what the claim is defined
     * to be, and because "which methods were used" survives a third one being
     * added in a way that {@code "mfa": true} does not.
     */
    private static final List<String> PASSWORD_ONLY = List.of("pwd");

    private static final List<String> PASSWORD_AND_OTP = List.of("pwd", "otp", "mfa");

    @Override
    public IssuedAccessToken issue(
            UUID userId, UUID sessionId, boolean emailVerified, boolean twoFactorAuthenticated, Instant now) {
        Instant expiresAt = now.plus(settings.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .audience(java.util.List.of(settings.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // A unique identifier per token. Nothing reads it yet; it is
                // what makes revoking one specific token possible later without
                // a migration, and what lets two log lines be tied together.
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString())
                .claim("email_verified", emailVerified)
                // Which methods this session was authenticated with. A payout
                // action requires "otp" to be here, and it has to come from the
                // session rather than from the account: switching two-factor on
                // does not retroactively make yesterday's sign-in a
                // two-factor one.
                .claim("amr", twoFactorAuthenticated ? PASSWORD_AND_OTP : PASSWORD_ONLY)
                .build();

        // No email, no name, no anything a person could be identified by. A JWT
        // is not encrypted: it is base64, and it is pasted into bug reports.
        return new IssuedAccessToken(
                encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                        .getTokenValue(),
                expiresAt);
    }
}
