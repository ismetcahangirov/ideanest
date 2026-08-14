package az.ideanest.auth.infrastructure;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AccessTokenIssuer;
import java.time.Instant;
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

    @Override
    public IssuedAccessToken issue(UUID userId, UUID sessionId, boolean emailVerified, Instant now) {
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
