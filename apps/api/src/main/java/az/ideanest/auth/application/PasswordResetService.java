package az.ideanest.auth.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AuthEvents.PasswordChanged;
import az.ideanest.auth.application.AuthEvents.PasswordResetRequested;
import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.domain.UserCredential;
import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.auth.infrastructure.VerificationTokenRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.SecureTokens;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.1's A-06: setting a new password without knowing the old one.
 *
 * <h2>The request tells the caller nothing</h2>
 *
 * <p>{@link #request} does the same visible thing for an address with an account and
 * an address without one, for the reason {@link RegistrationService} gives at length:
 * an endpoint that answers "no such account" is an enumeration oracle, and the list it
 * produces — which people on a breach list have accounts here — is the list somebody
 * wants before writing a phishing email.
 *
 * <p><strong>And unlike registration, nothing at all is sent to an address with no
 * account.</strong> Registration writes to an unknown-to-the-form address because that
 * address <em>is</em> registered and its owner deserves to know somebody is probing it.
 * Here the address is whatever was typed into a public form, so a message to it would
 * be this platform delivering mail to a stranger on an attacker's behalf. The rate
 * limits in {@code PasswordController} bound how often that form can be spent either
 * way.
 *
 * <h2>The link is single-use, short-lived, and retires its predecessor</h2>
 *
 * <p>One hour, from {@code ideanest.auth.password-reset-token-ttl} — the twenty-four
 * hours a verification link gets is deliberately not this: that link proves an address,
 * this one changes a credential, and a forwarded message should stop being a key to the
 * account long before it stops being a proof of the mailbox.
 *
 * <p>Asking twice invalidates the first link, using the statement
 * {@link VerificationTokenRepository#consumeOutstanding} was written for. Two live
 * links double the window in which a leaked message still works, and nobody ever
 * intends to use the older one.
 *
 * <h2>An account with no password can still reset one</h2>
 *
 * <p>Somebody who registered through Google or Apple (§17.1) has no
 * {@code user_credentials} row at all, and {@link #reset} creates one rather than
 * refusing. That is the documented way back for a person who has lost access to the
 * provider account they signed up with — the alternative is an account that can never
 * be reached again, which is a support ticket with no answer. It is not a weakening:
 * the proof required is control of the mailbox, which is what would recover the
 * provider account too.
 *
 * <h2>Every session dies</h2>
 *
 * <p>A password is reset precisely when somebody believes the old one is known, so the
 * sessions issued under it are the ones to be worried about. {@link SessionRevoker}
 * kills them in one statement rather than a loop, because a loop leaves a window in
 * which some are dead and some are not, and whoever is holding a survivor uses exactly
 * that window.
 */
@Service
public class PasswordResetService {

    private final UserAccounts users;
    private final UserCredentialRepository credentials;
    private final VerificationTokenRepository verificationTokens;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final SessionRevoker sessions;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final AuthProperties properties;
    private final Clock clock;

    public PasswordResetService(
            UserAccounts users,
            UserCredentialRepository credentials,
            VerificationTokenRepository verificationTokens,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            SessionRevoker sessions,
            ApplicationEventPublisher events,
            AuditLog audit,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.verificationTokens = verificationTokens;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.sessions = sessions;
        this.events = events;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issues a reset link, if the address has an account.
     *
     * <p>Returns nothing in both cases, which is the whole design. See the class
     * comment.
     */
    @Transactional
    public void request(EmailAddress email) {
        Instant now = clock.instant();
        Optional<UserAccount> account = users.findByEmail(email);

        if (account.isEmpty()) {
            /*
             * NOTHING IS WRITTEN AND NOTHING IS SENT, and the audit row that would say
             * "somebody asked about an address we have never seen" cannot be written
             * either: `audit_logs.entity_id` is NOT NULL by V21's argument that a row
             * which does not say what it was done to cannot be found by anyone looking
             * for it, and there is no account here to name. Inventing an identifier to
             * satisfy the column would put a row in that table pointing at nothing.
             *
             * What bounds this path is therefore the rate limiter in `PasswordController`
             * — per source address and per email address, the same pair registration uses
             * — rather than anything observable afterwards. Making enumeration attempts
             * visible in their own right needs a counter rather than an audit row, and
             * that is §18.2's metrics rather than this table.
             */
            return;
        }

        UserAccount user = account.get();

        /*
         * A SUSPENDED ACCOUNT MAY STILL RESET ITS PASSWORD, and an account inside its
         * deletion grace period may too. Neither is a security decision this endpoint
         * gets to make: sign-in refuses a suspended account regardless of what its
         * password is (AccountSuspendedException), and somebody who has asked to close
         * their account may well be trying to sign in to cancel that. Refusing here
         * would also make the response differ by account state, which is the oracle the
         * whole method is arranged to avoid.
         */
        verificationTokens.consumeOutstanding(user.id(), VerificationPurpose.PASSWORD_RESET, now);

        // The value exists here and in the message, and nowhere else. What is stored is
        // its hash, so this row cannot be turned back into a link.
        String token = SecureTokens.generate();
        verificationTokens.save(VerificationToken.issue(
                user.id(),
                VerificationPurpose.PASSWORD_RESET,
                SecureTokens.hash(token),
                now,
                now.plus(properties.passwordResetTokenTtl())));

        audit.record(
                AuditAction.PASSWORD_RESET_REQUESTED,
                user.id(),
                AuditActor.user(user.id()),
                AuditOutcome.SUCCEEDED,
                "reset link issued");

        // The account's language, not the request's. Somebody asking for a reset is
        // frequently on a borrowed device.
        events.publishEvent(new PasswordResetRequested(user.email(), token, user.locale()));
    }

    /**
     * Spends a link and sets the new password.
     *
     * @throws VerificationRejectedException for a token that is not one, is for
     *     something else, has expired, or has already been spent. All four say the same
     *     sentence for the first three and a different one for the fourth, exactly as
     *     {@link EmailVerificationService} does — "already used" is a fact the person
     *     holding the link needs, and it discloses nothing they do not have
     * @throws WeakPasswordException if the new password does not satisfy
     *     {@link PasswordPolicy}
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = clock.instant();

        VerificationToken token = verificationTokens
                .findByTokenHash(SecureTokens.hash(rawToken))
                .orElseThrow(() -> new VerificationRejectedException("This link is not valid."));

        if (token.getPurpose() != VerificationPurpose.PASSWORD_RESET) {
            // An address verification must not set a password. Email is the channel an
            // attacker with mailbox access already controls, and the purpose column is
            // what stops one capability becoming the other.
            throw new VerificationRejectedException("This link is not valid.");
        }
        if (token.isExpired(now)) {
            throw new VerificationRejectedException("This link has expired. Ask for a new one.");
        }

        UserAccount account = users.findById(token.getUserId())
                // The token is genuine and the account behind it has been closed. Said
                // the same way as an invalid link, because the person holding a link to
                // a deleted account has nothing useful to do with either answer.
                .orElseThrow(() -> new VerificationRejectedException("This link is not valid."));

        /*
         * The policy is checked BEFORE the token is claimed. A password the policy
         * refuses would otherwise burn the link on the way to a 400, and the person
         * fixing their typo would find the link dead — which is the reset flow's most
         * common support ticket, self-inflicted.
         */
        passwordPolicy.check(newPassword, account.email());

        // Claimed with a conditional update rather than a read and a write. Two clicks
        // arriving together would both see an unspent token; the database decides which
        // wins, and it can only decide when the condition is in the statement.
        if (verificationTokens.claim(token.getId(), now) == 0) {
            throw new VerificationRejectedException("This link has already been used.");
        }

        String hash = passwordHasher.hash(newPassword);
        credentials
                .findById(account.id())
                .ifPresentOrElse(
                        credential -> credential.changePassword(hash, passwordHasher.algorithm(), now),
                        // No credential row: an account that has only ever signed in
                        // through a provider. See the class comment for why this is the
                        // documented way back rather than a hole.
                        () -> credentials.save(
                                UserCredential.of(account.id(), hash, passwordHasher.algorithm(), now)));

        sessions.revokeAllFor(account.id(), SessionRevocationReason.PASSWORD_CHANGED, now);

        audit.record(
                AuditAction.PASSWORD_RESET,
                account.id(),
                AuditActor.user(account.id()),
                AuditOutcome.SUCCEEDED,
                "password set from a reset link");

        events.publishEvent(new PasswordChanged(account.email(), account.locale()));
    }
}
