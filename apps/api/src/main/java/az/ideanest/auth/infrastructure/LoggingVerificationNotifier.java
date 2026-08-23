package az.ideanest.auth.infrastructure;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.VerificationNotifier;
import az.ideanest.shared.EmailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stand-in until transactional email exists (#86).
 *
 * <p>It writes a line saying a message would have been sent. It writes the
 * token itself <strong>only</strong> when {@code ideanest.auth.log-verification-links}
 * is on, which it is in {@code local} and nowhere else — a verification link in
 * a production log is an account takeover for anybody who can read logs, and
 * the set of people who can read logs is always larger than it looks.
 *
 * <p>Without a real sender, a deployed environment cannot complete
 * registration. That is the correct state for it to be in: it fails visibly,
 * rather than appearing to work while nothing arrives.
 */
@Component
public class LoggingVerificationNotifier implements VerificationNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationNotifier.class);

    private final boolean logLinks;

    public LoggingVerificationNotifier(AuthProperties properties) {
        this.logLinks = properties.logVerificationLinks();
    }

    @Override
    public void sendEmailVerification(EmailAddress email, String token, String locale) {
        if (logLinks) {
            log.info("Verification token for {} ({}): {}", email, locale, token);
        } else {
            // EmailAddress masks itself, so this line names an account without
            // publishing the address.
            log.info("Verification email queued for {} ({}). No mail transport is configured (#86).", email, locale);
        }
    }

    @Override
    public void sendRegistrationAttemptOnExistingAccount(EmailAddress email, String locale) {
        log.info("Registration attempted on the existing account {} ({}).", email, locale);
    }

    @Override
    public void sendPasswordReset(EmailAddress email, String token, String locale) {
        if (logLinks) {
            log.info("Password reset token for {} ({}): {}", email, locale, token);
        } else {
            log.info("Password reset email queued for {} ({}). No mail transport is configured (#86).", email, locale);
        }
    }

    @Override
    public void sendPasswordChanged(EmailAddress email, String locale) {
        // Never gated on `logLinks`: there is no link in it. The line is worth
        // having in every environment, because "the password changed and nobody
        // was told" is the failure this message exists to make visible.
        log.info("Password change notice queued for {} ({}). No mail transport is configured (#86).", email, locale);
    }

    @Override
    public void sendEmailChangeConfirmation(EmailAddress newEmail, String token, String locale) {
        if (logLinks) {
            log.info("Email change token for {} ({}): {}", newEmail, locale, token);
        } else {
            log.info(
                    "Email change confirmation queued for {} ({}). No mail transport is configured (#86).",
                    newEmail,
                    locale);
        }
    }

    @Override
    public void sendEmailChangeNotice(EmailAddress previousEmail, EmailAddress newEmail, String locale) {
        // Both addresses masked by EmailAddress.toString, which is the whole
        // reason this line can name them at all.
        log.info(
                "Email change notice queued for {} (moving to {}, {}). No mail transport is configured (#86).",
                previousEmail,
                newEmail,
                locale);
    }
}
