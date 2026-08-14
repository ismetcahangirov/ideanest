package az.ideanest.auth.application;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AccessTokenIssuer.IssuedAccessToken;
import az.ideanest.auth.domain.RefreshToken;
import az.ideanest.auth.domain.SecureTokens;
import az.ideanest.auth.domain.Session;
import az.ideanest.auth.domain.UserCredential;
import az.ideanest.auth.infrastructure.RefreshTokenRepository;
import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exchanging an email address and a password for a session. */
@Service
public class SignInService {

    /**
     * One message for every way of failing. Which of them happened is not the
     * caller's business, and telling them turns sign-in into the account
     * enumeration oracle that registration is written to avoid.
     */
    private static final String REFUSAL = "Those credentials are not valid.";

    private final UserAccounts users;
    private final UserCredentialRepository credentials;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties properties;
    private final Clock clock;

    /**
     * A hash of a password nobody has, verified against when no account exists.
     *
     * <p>Without it, "no such account" returns in a millisecond and "wrong
     * password" takes as long as Argon2 does. That difference is measurable
     * over the network, and it answers "does this address have an account
     * here?" for anybody willing to time it — the same question the identical
     * error message refuses to answer.
     */
    private final String decoyHash;

    public SignInService(
            UserAccounts users,
            UserCredentialRepository credentials,
            SessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            PasswordHasher passwordHasher,
            AccessTokenIssuer accessTokens,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
        this.decoyHash = passwordHasher.hash(SecureTokens.generate());
    }

    /**
     * @param deviceLabel what the user will see in their device list
     * @param userAgent recorded for the same reason
     * @param ipAddress recorded so that an unexpected session can be recognised
     */
    public record SignInCommand(
            EmailAddress email, String password, String deviceLabel, String userAgent, String ipAddress) {
    }

    @Transactional
    public IssuedTokens signIn(SignInCommand command) {
        Instant now = clock.instant();

        Optional<UserAccount> account = users.findByEmail(command.email());
        Optional<UserCredential> credential = account.flatMap(found -> credentials.findById(found.id()));

        if (credential.isEmpty()) {
            // Spend the same time as a real verification would, then refuse.
            passwordHasher.matches(command.password(), decoyHash);
            throw new AuthenticationFailedException(REFUSAL);
        }

        UserCredential stored = credential.get();
        if (!passwordHasher.matches(command.password(), stored.getPasswordHash())) {
            throw new AuthenticationFailedException(REFUSAL);
        }

        if (passwordHasher.needsRehash(stored.getPasswordHash())) {
            // The only moment the password is in hand. Rehashing here is what
            // makes raising the Argon2 parameters a gradual migration instead
            // of a decision that only applies to accounts created afterwards.
            stored.rehash(passwordHasher.hash(command.password()), passwordHasher.algorithm());
            credentials.save(stored);
        }

        // Sign-in is deliberately allowed before the address is verified. The
        // account can be used to browse and to be reminded; what verification
        // gates is money and messaging, and those checks read the claim.
        return startSession(account.orElseThrow(), command, now);
    }

    private IssuedTokens startSession(UserAccount account, SignInCommand command, Instant now) {
        Instant expiresAt = now.plus(properties.token().refreshTokenTtl());

        Session session = sessions.save(Session.start(account.id(), now, expiresAt)
                .describedAs(command.deviceLabel(), command.userAgent(), command.ipAddress()));

        String refreshToken = SecureTokens.generate();
        refreshTokens.save(
                RefreshToken.issue(session.getId(), SecureTokens.hash(refreshToken), now, expiresAt));

        IssuedAccessToken accessToken =
                accessTokens.issue(account.id(), session.getId(), account.emailVerified(), now);

        return new IssuedTokens(
                accessToken.value(), accessToken.expiresAt(), refreshToken, expiresAt, session.getId());
    }
}
