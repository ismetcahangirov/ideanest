package az.ideanest.auth.application;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.domain.Base32;
import az.ideanest.auth.domain.RecoveryCode;
import az.ideanest.auth.domain.RecoveryCodes;
import az.ideanest.auth.domain.Totp;
import az.ideanest.auth.domain.TwoFactorSecret;
import az.ideanest.auth.infrastructure.RecoveryCodeRepository;
import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.TwoFactorSecretRepository;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Switching two-factor authentication on, and off again.
 *
 * <p>Three rules shape all of it.
 *
 * <p><strong>Enrolling is not enabling.</strong> Starting an enrolment writes a
 * secret and nothing else; two-factor is off until a current code has been
 * entered. Anything else locks out the user whose phone dies between scanning
 * the code and typing one, and a lockout on a funding platform means somebody
 * cannot reach their money.
 *
 * <p><strong>Every change costs the password.</strong> Starting an enrolment and
 * switching it off both require it, so that a stolen access token — fifteen
 * minutes of one, taken from a bug report or a proxy log — cannot be used to
 * bolt a second factor onto somebody's account or to take theirs away.
 *
 * <p><strong>Switching off costs a code as well.</strong> Otherwise the whole
 * feature is worth exactly one password, which is the thing it was added to
 * stop being enough.
 */
@Service
public class TwoFactorEnrolmentService {

    /** One refusal for a wrong password and for a wrong code alike. */
    private static final String REFUSAL = "That password or code is not valid.";

    private final TwoFactorSecretRepository secrets;
    private final RecoveryCodeRepository recoveryCodes;
    private final UserCredentialRepository credentials;
    private final SessionRepository sessions;
    private final SecondFactors secondFactors;
    private final PasswordHasher passwordHasher;
    private final UserAccounts users;
    private final AuthProperties properties;
    private final Clock clock;

    public TwoFactorEnrolmentService(
            TwoFactorSecretRepository secrets,
            RecoveryCodeRepository recoveryCodes,
            UserCredentialRepository credentials,
            SessionRepository sessions,
            SecondFactors secondFactors,
            PasswordHasher passwordHasher,
            UserAccounts users,
            AuthProperties properties,
            Clock clock) {
        this.secrets = secrets;
        this.recoveryCodes = recoveryCodes;
        this.credentials = credentials;
        this.sessions = sessions;
        this.secondFactors = secondFactors;
        this.passwordHasher = passwordHasher;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Generates a secret and returns it once.
     *
     * <p>Starting again before confirming replaces the secret. A user who
     * abandoned the flow on one device and started it on another has two
     * pictures and only one of them can be right; the newest is the one they
     * are looking at.
     */
    @Transactional
    public TwoFactorEnrolment start(UUID userId, String password) {
        requirePassword(userId, password);

        UserAccount account = users.findById(userId).orElseThrow(() -> new TwoFactorRejectedException(REFUSAL));

        TwoFactorSecret enrolment = secrets.findById(userId)
                .map(existing -> {
                    if (existing.isConfirmed()) {
                        // Not the same message as a wrong password: this caller
                        // has already proved the password, and there is nothing
                        // to withhold from the owner of the account.
                        throw new TwoFactorRejectedException("Two-factor authentication is already enabled.");
                    }
                    existing.restart(Totp.newSecret());
                    return existing;
                })
                .orElseGet(() -> TwoFactorSecret.enrol(userId, Totp.newSecret()));

        secrets.save(enrolment);

        byte[] secret = enrolment.getSecret();
        return new TwoFactorEnrolment(Base32.encode(secret), otpauthUri(account, secret));
    }

    /**
     * Turns the enrolment into a second factor and issues the recovery codes.
     *
     * @param sessionId the session this request arrived on, which is marked as
     *     two-factor authenticated: a code was just entered on it, and forcing
     *     the user to sign in again to reach a payout would be ceremony rather
     *     than security
     * @return the recovery codes, in the clear, for the only time they exist
     */
    @Transactional
    public List<String> confirm(UUID userId, UUID sessionId, String code) {
        Instant now = clock.instant();

        TwoFactorSecret enrolment = secrets.findById(userId)
                .filter(found -> !found.isConfirmed())
                .orElseThrow(() -> new TwoFactorRejectedException(
                        "There is no enrolment to confirm. Start one first."));

        OptionalLong step = Totp.verify(enrolment.getSecret(), code, now);
        if (step.isEmpty()) {
            throw new TwoFactorRejectedException(REFUSAL);
        }

        // The proving step is recorded as spent, so the code that switched
        // two-factor on cannot then be used to sign in with it.
        enrolment.confirm(now, step.getAsLong());
        secrets.save(enrolment);

        List<String> issued = issueRecoveryCodes(userId, now);

        sessions.findById(sessionId)
                .filter(session -> session.getUserId().equals(userId))
                .ifPresent(session -> sessions.save(session.withSecondFactor(now)));

        return issued;
    }

    /**
     * Switches two-factor off. Costs the current password <em>and</em> a code.
     *
     * <p>The secret and the recovery codes are deleted rather than marked
     * inactive. A disabled row that still matches codes is a way back in that
     * nobody is looking at, and re-enrolment produces a new secret anyway.
     */
    @Transactional
    public void disable(UUID userId, String password, String code, String recoveryCode) {
        Instant now = clock.instant();

        requirePassword(userId, password);

        TwoFactorSecret enrolment = secrets.findByUserIdAndConfirmedAtIsNotNull(userId)
                .orElseThrow(() -> new TwoFactorRejectedException("Two-factor authentication is not enabled."));

        if (!secondFactors.accepts(enrolment, code, recoveryCode, now)) {
            throw new TwoFactorRejectedException(REFUSAL);
        }

        recoveryCodes.deleteAllForUser(userId);
        secrets.delete(enrolment);
    }

    private List<String> issueRecoveryCodes(UUID userId, Instant now) {
        // Any earlier set is discarded first. Two live sets would mean a code
        // printed a year ago still works after the user asked for new ones,
        // which is the opposite of what asking for new ones means.
        recoveryCodes.deleteAllForUser(userId);

        List<String> issued = new ArrayList<>(RecoveryCodes.COUNT);
        for (int index = 0; index < RecoveryCodes.COUNT; index++) {
            String code = RecoveryCodes.generate();
            issued.add(code);
            recoveryCodes.save(RecoveryCode.issue(userId, RecoveryCodes.hash(code), now));
        }
        return issued;
    }

    private void requirePassword(UUID userId, String password) {
        Optional<String> hash = credentials.findById(userId).map(credential -> credential.getPasswordHash());
        if (hash.isEmpty() || !passwordHasher.matches(password, hash.get())) {
            throw new TwoFactorRejectedException(REFUSAL);
        }
    }

    /**
     * The {@code otpauth://} URI, as the Key URI Format defines it.
     *
     * <p>The label carries the issuer as well as the address, because an
     * authenticator showing six digits under "person@example.com" and nothing
     * else is useless to anybody with two accounts.
     */
    private String otpauthUri(UserAccount account, byte[] secret) {
        String issuer = properties.twoFactor().issuer();
        String label = encode(issuer) + ":" + encode(account.email().value());

        return "otpauth://totp/" + label
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + encode(issuer)
                // Stated even though they are the defaults. Several
                // authenticators ignore them and assume exactly these values,
                // and the ones that read them should not have to guess.
                + "&algorithm=SHA1"
                + "&digits=" + Totp.DIGITS
                + "&period=" + Totp.PERIOD.toSeconds();
    }

    private static String encode(String value) {
        // URLEncoder is form encoding, which spells a space '+'. In a URI path
        // that is a literal plus sign, and the label would then contain one.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
