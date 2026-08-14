package az.ideanest.support;

import az.ideanest.auth.application.VerificationNotifier;
import az.ideanest.shared.EmailAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A notifier that remembers instead of sending.
 *
 * <p>The verification token exists in exactly two places: the message sent to
 * the person registering, and its hash in the database. A test that wanted to
 * verify an address by reading the row would prove only that <em>a</em> token
 * exists — not that the right one was sent to the right address. So the test
 * reads what was sent.
 */
public class RecordingVerificationNotifier implements VerificationNotifier {

    /** One sent verification link. */
    public record SentVerification(EmailAddress email, String token, String locale) {
    }

    private final List<SentVerification> verifications = new CopyOnWriteArrayList<>();
    private final List<EmailAddress> existingAccountWarnings = new CopyOnWriteArrayList<>();

    @Override
    public void sendEmailVerification(EmailAddress email, String token, String locale) {
        verifications.add(new SentVerification(email, token, locale));
    }

    @Override
    public void sendRegistrationAttemptOnExistingAccount(EmailAddress email, String locale) {
        existingAccountWarnings.add(email);
    }

    /** The most recent token sent to an address, if any. */
    public Optional<String> tokenSentTo(EmailAddress email) {
        return verifications.stream()
                .filter(sent -> sent.email().equals(email))
                .map(SentVerification::token)
                .reduce((first, second) -> second);
    }

    public long verificationsSentTo(EmailAddress email) {
        return verifications.stream().filter(sent -> sent.email().equals(email)).count();
    }

    public long warningsSentTo(EmailAddress email) {
        return existingAccountWarnings.stream().filter(email::equals).count();
    }

    public void clear() {
        verifications.clear();
        existingAccountWarnings.clear();
    }
}
