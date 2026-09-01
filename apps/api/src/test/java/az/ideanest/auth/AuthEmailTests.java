package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.VerificationNotifier;
import az.ideanest.auth.infrastructure.AuthEmailComposer;
import az.ideanest.auth.infrastructure.SmtpVerificationNotifier;
import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.TransactionalMail;
import az.ideanest.notification.application.TransactionalMailFailedException;
import az.ideanest.notification.application.TransactionalMailer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.MailServerStub;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

/**
 * That the six auth messages actually leave the process — issue #86's other half.
 *
 * <p>Against {@code MailServerStub}, a real SMTP server, for the reason
 * {@code EmailTransportTests} gives: what is worth asserting here is a property of the
 * bytes. A mocked mailer would prove that {@code SmtpVerificationNotifier} called
 * {@code send}, and the defect this suite exists to catch is not that — it is a link built
 * against the wrong origin, a token that did not survive the query string, or a footer that
 * offers to switch off a password-reset email.
 *
 * <p><strong>The adapter is injected by its own type, not through the port.</strong>
 * {@code TestDoublesConfiguration} publishes {@code RecordingVerificationNotifier} as
 * {@code @Primary}, so everything else in the suite still gets the recorder and still reads
 * tokens out of it. That is deliberate and stays: the registration path's own assertions
 * are about which token reached which address, not about MIME. This suite asks the
 * question the recorder cannot — and {@link #exactlyOneAdapterIsShipped()} is what keeps
 * the two facts joined up, by failing if production ever has some other notifier again.
 */
class AuthEmailTests extends AbstractIntegrationTest {

    private static final EmailAddress RECIPIENT = EmailAddress.of("verify-target@example.com");

    private static final EmailAddress PREVIOUS = EmailAddress.of("old-address@example.com");

    private static final String TOKEN = "tYXqQz3-token_value";

    @Autowired
    private SmtpVerificationNotifier notifier;

    @Autowired
    private AuthEmailComposer composer;

    @Autowired
    private NotificationProperties notifications;

    @Autowired
    private AuthProperties auth;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MessageSource catalogue;

    @BeforeEach
    void emptyTheMailbox() {
        // Shared server, so a test that reads the mailbox clears it first.
        MailServerStub.clear();
    }

    private String origin() {
        return notifications.email().baseUrl();
    }

    // ------------------------------------------------------------------
    // The links
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a verification link arrives, and carries the token that was issued")
    void verificationLinkArrives() throws Exception {
        notifier.sendEmailVerification(RECIPIENT, TOKEN, "az");

        MimeMessage message = MailServerStub.awaitOne();

        assertThat(addressesOf(message)).containsExactly(RECIPIENT.value());
        assertThat(message.getSubject()).isNotBlank();
        // Both parts, because a client picks one and neither may be the broken one.
        assertThat(partsOf(message)).hasSize(2).allSatisfy(part -> assertThat(part)
                .contains(origin() + "/verify-email?token=" + TOKEN));
    }

    @Test
    @DisplayName("a reset link points at the form that takes a token, not at the one that asks for one")
    void resetLinkPointsAtTheConfirmForm() throws Exception {
        notifier.sendPasswordReset(RECIPIENT, TOKEN, "az");

        // /reset-password is where somebody asks for a link; /reset-password/confirm is
        // where they spend one. Sending the second person to the first is a loop.
        assertThat(partsOf(MailServerStub.awaitOne()))
                .allSatisfy(part -> assertThat(part).contains(origin() + "/reset-password/confirm?token=" + TOKEN));
    }

    @Test
    @DisplayName("an address-change link goes to the address being proven")
    void addressChangeLinkArrives() throws Exception {
        notifier.sendEmailChangeConfirmation(RECIPIENT, TOKEN, "az");

        MimeMessage message = MailServerStub.awaitOne();

        assertThat(addressesOf(message)).containsExactly(RECIPIENT.value());
        assertThat(partsOf(message))
                .allSatisfy(part -> assertThat(part).contains(origin() + "/confirm-email-change?token=" + TOKEN));
    }

    @Test
    @DisplayName("a token with characters a URL reserves survives the query string")
    void tokenIsEncoded() throws Exception {
        // SecureTokens is base64url today and has no reserved characters in it. This is
        // about the day it stops being: a token containing '+' concatenated into a query
        // string arrives as a space, and the link fails for one user in sixty-four with
        // an error that says the link is invalid.
        notifier.sendEmailVerification(RECIPIENT, "a+b/c=d", "az");

        assertThat(partsOf(MailServerStub.awaitOne()))
                .allSatisfy(part -> assertThat(part).contains("token=a%2Bb%2Fc%3Dd"));
    }

    // ------------------------------------------------------------------
    // The two messages that carry no link
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the notice to the address an account is leaving names the address it is going to")
    void changeNoticeNamesTheNewAddress() throws Exception {
        notifier.sendEmailChangeNotice(PREVIOUS, RECIPIENT, "az");

        MimeMessage message = MailServerStub.awaitOne();

        assertThat(addressesOf(message)).containsExactly(PREVIOUS.value());
        assertThat(partsOf(message)).allSatisfy(part -> assertThat(part)
                .as("the one fact this message exists to carry")
                .contains(RECIPIENT.value()));
    }

    @Test
    @DisplayName("the password-change notice offers the reader who did not do it a way back in")
    void passwordChangeNoticeOffersTheResetForm() throws Exception {
        notifier.sendPasswordChanged(RECIPIENT, "az");

        assertThat(partsOf(MailServerStub.awaitOne()))
                .allSatisfy(part -> assertThat(part).contains(origin() + "/reset-password"));
    }

    @Test
    @DisplayName("the registration notice goes to the owner of the address, and creates nothing")
    void registrationNoticeGoesToTheOwner() throws Exception {
        notifier.sendRegistrationAttemptOnExistingAccount(RECIPIENT, "az");

        MimeMessage message = MailServerStub.awaitOne();

        assertThat(addressesOf(message)).containsExactly(RECIPIENT.value());
        assertThat(partsOf(message)).allSatisfy(part -> assertThat(part).contains(origin() + "/reset-password"));
    }

    // ------------------------------------------------------------------
    // The footer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an auth email does not offer to switch itself off")
    void noPreferencesLine() throws Exception {
        notifier.sendPasswordReset(RECIPIENT, TOKEN, "az");

        List<String> parts = partsOf(MailServerStub.awaitOne());

        // The line is the notification footer's second sentence, and it is false here:
        // there is no preference behind a password reset and there must not be one.
        String preferences = catalogue.getMessage("email.layout.preferences", null, Locale.forLanguageTag("az"));
        String footer = catalogue.getMessage("email.layout.footer", null, Locale.forLanguageTag("az"));

        assertThat(parts).allSatisfy(part -> assertThat(part).doesNotContain(preferences));
        // And the first sentence is still there, so this is an omission rather than a
        // footer that failed to render.
        assertThat(parts).allSatisfy(part -> assertThat(part).contains(footer));
    }

    // ------------------------------------------------------------------
    // What happens when the relay will not take it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a refused send does not propagate, because the account already exists")
    void aRefusalIsSwallowed() {
        // Constructed rather than autowired: the point is a mailer that fails, and the
        // shared context's is a working one. VerificationNotificationListener runs
        // AFTER_COMMIT, so anything thrown here reaches a caller whose registration has
        // already succeeded -- see SmtpVerificationNotifier for the whole argument.
        VerificationNotifier refusing = new SmtpVerificationNotifier(
                new RefusingMailer(), composer, auth);

        assertThatCode(() -> refusing.sendEmailVerification(RECIPIENT, TOKEN, "az"))
                .doesNotThrowAnyException();
        assertThatCode(() -> refusing.sendPasswordReset(RECIPIENT, TOKEN, "az"))
                .doesNotThrowAnyException();
        assertThatCode(() -> refusing.sendPasswordChanged(RECIPIENT, "az")).doesNotThrowAnyException();
        assertThatCode(() -> refusing.sendEmailChangeConfirmation(RECIPIENT, TOKEN, "az"))
                .doesNotThrowAnyException();
        assertThatCode(() -> refusing.sendEmailChangeNotice(PREVIOUS, RECIPIENT, "az"))
                .doesNotThrowAnyException();
        assertThatCode(() -> refusing.sendRegistrationAttemptOnExistingAccount(RECIPIENT, "az"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // The wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("production ships exactly one notifier, and it is the one that sends")
    void exactlyOneAdapterIsShipped() {
        Map<String, VerificationNotifier> notifiers = context.getBeansOfType(VerificationNotifier.class);

        // The recorder is the suite's, and is @Primary so that every other auth test keeps
        // reading tokens off it. What must not come back is a second production adapter:
        // the log stand-in this feature replaced was one, and two @Components implementing
        // one port is an injection failure at start-up rather than a test failure here.
        assertThat(notifiers.values())
                .filteredOn(candidate -> candidate.getClass().getName().startsWith("az.ideanest.auth"))
                .singleElement()
                .isInstanceOf(SmtpVerificationNotifier.class);
    }

    /** A mailer that will not take anything, standing in for a relay that is down. */
    private static final class RefusingMailer implements TransactionalMailer {

        @Override
        public void send(String toAddress, String toName, TransactionalMail mail, Locale locale) {
            throw new TransactionalMailFailedException(
                    "The relay refused a transactional email", new IllegalStateException("no relay"));
        }
    }

    private static List<String> addressesOf(MimeMessage message) throws Exception {
        return List.of(message.getAllRecipients()).stream().map(Object::toString).toList();
    }

    /** The bodies of a {@code multipart/alternative}, in the order they were written. */
    private static List<String> partsOf(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof Multipart multipart)) {
            throw new AssertionError("Expected multipart/alternative and got " + message.getContentType());
        }
        return partsIn(multipart);
    }

    private static List<String> partsIn(Multipart multipart) throws Exception {
        List<String> parts = new ArrayList<>();
        for (int part = 0; part < multipart.getCount(); part++) {
            Object body = multipart.getBodyPart(part).getContent();
            if (body instanceof Multipart nested) {
                parts.addAll(partsIn(nested));
            } else {
                parts.add(body.toString());
            }
        }
        return parts;
    }
}
