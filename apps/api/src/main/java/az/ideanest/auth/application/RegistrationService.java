package az.ideanest.auth.application;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AuthEvents.EmailVerificationRequested;
import az.ideanest.auth.application.AuthEvents.RegistrationAttemptedOnExistingAccount;
import az.ideanest.shared.SecureTokens;
import az.ideanest.auth.domain.UserCredential;
import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.auth.infrastructure.VerificationTokenRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration with an email address and a password.
 *
 * <p><strong>The response does not reveal whether the address is already
 * registered.</strong> An endpoint that answers "that email is taken" is an
 * account enumeration oracle: feed it a breach list and it returns the subset
 * of those people who are backers here, which is a list worth having before
 * writing a phishing email. So both paths do the same visible thing, and the
 * only difference is which message the address receives — and that goes to the
 * person who owns it, not to the person who typed it.
 *
 * <p>The cost is real: the sign-up form cannot say "you already have an
 * account". The mitigation is that the email says exactly that, with a link to
 * sign in and a link to reset the password.
 */
@Service
public class RegistrationService {

    private final UserAccounts users;
    private final UserCredentialRepository credentials;
    private final VerificationTokenRepository verificationTokens;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final ApplicationEventPublisher events;
    private final AuthProperties properties;
    private final Clock clock;

    public RegistrationService(
            UserAccounts users,
            UserCredentialRepository credentials,
            VerificationTokenRepository verificationTokens,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            ApplicationEventPublisher events,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.verificationTokens = verificationTokens;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param email the address, already normalised
     * @param password the raw password. Never stored, never logged, and out of
     *     scope the moment this method returns
     * @param name the display name a slug is derived from
     * @param locale which language the verification email is written in
     * @param currency what amounts are displayed in
     */
    public record RegistrationCommand(
            EmailAddress email, String password, String name, String locale, String currency) {
    }

    @Transactional
    public void register(RegistrationCommand command) {
        // Before anything is written, and before the address is looked up: a
        // rejected password should not depend on whether the account exists,
        // because the timing difference is itself an oracle.
        passwordPolicy.check(command.password(), command.email());

        Instant now = clock.instant();

        if (users.isEmailTaken(command.email())) {
            events.publishEvent(new RegistrationAttemptedOnExistingAccount(command.email(), command.locale()));
            return;
        }

        UserAccount account = users.register(command.email(), command.name(), command.locale(), command.currency());

        credentials.save(UserCredential.of(
                account.id(), passwordHasher.hash(command.password()), passwordHasher.algorithm(), now));

        // The value exists here and in the email, and nowhere else. What is
        // stored is its hash, so this row cannot be turned back into a link.
        String token = SecureTokens.generate();
        verificationTokens.save(VerificationToken.issue(
                account.id(),
                VerificationPurpose.EMAIL_VERIFICATION,
                SecureTokens.hash(token),
                now,
                now.plus(properties.verificationTokenTtl())));

        events.publishEvent(new EmailVerificationRequested(command.email(), token, command.locale()));
    }
}
