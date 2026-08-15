package az.ideanest.auth.application;

import az.ideanest.shared.SecureTokens;
import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.auth.infrastructure.VerificationTokenRepository;
import az.ideanest.user.application.AccountSecurity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code user} asked for, answered from the tables {@code auth} owns.
 *
 * <p>The interface belongs to the other module and the implementation is here,
 * which is the wrong way round only if you read the package names. {@code auth}
 * already depends on {@code user}; a call in the opposite direction would make
 * the two a cycle. This way the dependency arrow does not change and neither
 * module reaches into the other's internals.
 */
@Service
public class AuthAccountSecurity implements AccountSecurity {

    private final UserCredentialRepository credentials;
    private final SessionRepository sessions;
    private final VerificationTokenRepository verificationTokens;
    private final PasswordHasher passwordHasher;

    /**
     * The same trick {@code SignInService} uses. An account with no credential
     * would otherwise be refused in a millisecond while a wrong password costs
     * Argon2, and the difference is measurable by whoever is holding the token.
     */
    private final String decoyHash;

    public AuthAccountSecurity(
            UserCredentialRepository credentials,
            SessionRepository sessions,
            VerificationTokenRepository verificationTokens,
            PasswordHasher passwordHasher) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.verificationTokens = verificationTokens;
        this.passwordHasher = passwordHasher;
        this.decoyHash = passwordHasher.hash(SecureTokens.generate());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean passwordMatches(UUID userId, String rawPassword) {
        return credentials
                .findById(userId)
                .map(credential -> passwordHasher.matches(rawPassword, credential.getPasswordHash()))
                .orElseGet(() -> {
                    passwordHasher.matches(rawPassword, decoyHash);
                    return false;
                });
    }

    @Override
    @Transactional
    public void endAllSessions(UUID userId, Instant at) {
        // The repository rather than SessionRevoker: the revoker commits in its
        // own transaction, which is right when the caller is about to throw and
        // wrong here. If the deletion request that triggered this fails, the
        // user should not be left signed out of a deletion that did not happen.
        sessions.revokeAllForUser(userId, SessionRevocationReason.USER_REVOKED, at);
    }

    @Override
    @Transactional
    public void forget(UUID userId) {
        // The password hash goes. It is not a record of anything — it is a
        // credential for an account that can no longer be signed in to, and
        // people reuse passwords, so keeping it is a liability to somebody's
        // other accounts and an asset to nobody.
        credentials.deleteById(userId);
        verificationTokens.deleteAllForUser(userId);
        sessions.stripPersonalDetails(userId);
        // Refresh tokens are left alone. A row there is a SHA-256 of 256 bits we
        // generated plus two timestamps: it identifies nobody, and V2 keeps
        // spent tokens on purpose so that a replay is distinguishable from a
        // token that never existed.
    }

    @Override
    @Transactional(readOnly = true)
    public SecurityHistory historyFor(UUID userId) {
        List<SessionRecord> sessionRecords = sessions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(session -> new SessionRecord(
                        session.getId(),
                        session.getDeviceLabel(),
                        session.getUserAgent(),
                        session.getIpAddress(),
                        session.getCreatedAt(),
                        session.getLastSeenAt(),
                        session.getExpiresAt(),
                        session.getRevokedAt(),
                        Objects.toString(session.getRevokedReason(), null)))
                .toList();

        List<VerificationRecord> verificationRecords = verificationTokens.findByUserIdOrderByCreatedAt(userId).stream()
                // The hash is not exported. It is a credential, not a fact about
                // the person, and it is the one field on the row that could be
                // used against the account.
                .map(token -> new VerificationRecord(
                        token.getPurpose().name(),
                        token.getCreatedAt(),
                        token.getExpiresAt(),
                        token.getConsumedAt()))
                .toList();

        return new SecurityHistory(sessionRecords, verificationRecords);
    }
}
