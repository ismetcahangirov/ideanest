package az.ideanest.notification.infrastructure;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.application.NotificationDigest;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.application.PermanentDeliveryFailure;
import az.ideanest.notification.domain.EmailDelivery;
import az.ideanest.notification.domain.EmailDeliveryOutcome;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import az.ideanest.shared.ReaderLocale;
import java.util.Locale;

/**
 * §4.10's email column, with a transport behind it — #86.
 *
 * <p>This class is the whole of the wiring {@code ChannelSenderConfiguration} described:
 * a {@code @Component} implementing {@link ChannelSender} and returning
 * {@link NotificationChannel#EMAIL}, replacing the {@code UndeliverableChannelSender}
 * bean that stood in for it. {@code NotificationDispatch} finds senders by channel and
 * nothing else in the module changed.
 *
 * <h2>The contract, and the third answer</h2>
 *
 * <p>{@link ChannelSender} gives two outcomes: returning means the relay took the
 * message, throwing means it did not. Both are honoured exactly. There is a third case
 * that neither describes — the recipient has no address at all, because the account was
 * deleted and §17.4's anonymisation rewrote it to a {@code .invalid} one — and it is
 * reported with {@link PermanentDeliveryFailure}, which dead-letters at once instead of
 * spending eight attempts discovering that a deleted account is still deleted.
 *
 * <h2>What "accepted" means, in one sentence</h2>
 *
 * <p>The relay took it. Not that it arrived, not that it escaped a spam filter, and not
 * that anybody read it. {@code EmailDeliveryOutcome} and V30's header both refuse to name
 * a stronger fact than SMTP can support, and neither does this class.
 *
 * <h2>Duplicates</h2>
 *
 * <p>The queue is at-least-once by design and this sender will be handed the same message
 * twice — {@code NotificationDispatch} argues why that is the right trade. What is done
 * about it is the {@code Message-ID}: derived from {@code notifications.id}, which is
 * stable across every attempt, so the second copy carries the identifier of the first.
 * Conforming mail clients and stores collapse those. <strong>That reduces duplicates
 * rather than eliminating them</strong> — a relay may rewrite the header, and not every
 * client deduplicates — and the honest fix is a provider with idempotency keys, which is
 * not what §16 chose.
 *
 * <h2>Order of operations</h2>
 *
 * <p>Send first, then record. Both happen inside {@code NotificationDispatch}'s
 * transaction, so the delivery row and the notification's new state commit together or
 * neither does — and a crash in between is the duplicate the paragraph above is about,
 * rather than a message nobody was told about. V30's rollback note works through what
 * this ordering means if the table is dropped underneath a running release.
 */
@Component
public class EmailChannelSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);

    /**
     * RFC 2606 reserves the whole {@code .invalid} top-level domain as one that never
     * resolves, and {@code User.anonymise} writes an address in it. Matching the TLD
     * rather than the exact domain {@code anonymised.invalid} is deliberate: anything
     * else that ever lands in there is equally undeliverable, and a check that named one
     * domain would be a check that had to be remembered a second time.
     */
    private static final String UNDELIVERABLE_SUFFIX = ".invalid";

    private final MimeEmails mime;
    private final UserAccounts users;
    private final EmailComposer composer;
    private final EmailRenderer renderer;
    private final EmailDeliveryRepository deliveries;
    private final Clock clock;

    public EmailChannelSender(
            MimeEmails mime,
            UserAccounts users,
            EmailComposer composer,
            EmailRenderer renderer,
            EmailDeliveryRepository deliveries,
            Clock clock) {

        this.mime = mime;
        this.users = users;
        this.composer = composer;
        this.renderer = renderer;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(NotificationMessage message) {
        UserAccount recipient = addressFor(message.recipientId())
                .orElseThrow(() -> suppress(message));

        /*
         * THE LANGUAGE COMES OFF THE ACCOUNT, not off a request — issue #324. This runs on a
         * background sender where there is no request, and the person who triggered the event
         * is frequently not the person being written to: a reply notification is caused by
         * somebody else typing. `ReaderLocale` falls back to the primary language rather than
         * throwing on a tag it does not know.
         */
        Locale locale = ReaderLocale.of(recipient.locale());
        RenderedEmail email = renderer.render(composer.compose(message, recipient.name(), locale), locale);
        String messageId = mime.messageIdFor(message.id());

        try {
            mime.send(mime.build(recipient.email().value(), recipient.name(), email, messageId));
        } catch (MailException | MessagingException refused) {
            deliveries.save(EmailDelivery.notSent(
                    message.id(),
                    message.recipientId(),
                    message.type(),
                    EmailDeliveryOutcome.REFUSED,
                    message.attempt(),
                    email.subject(),
                    messageId,
                    describe(refused)));
            // Rethrown, because the contract says a channel reports failure by throwing
            // and NotificationDispatch is what counts the attempt and backs off. Wrapped
            // rather than propagated raw only when it is checked -- MessagingException is
            // not a RuntimeException and the interface does not declare it.
            throw refused instanceof RuntimeException unchecked
                    ? unchecked
                    : new MailDeliveryFailedException(refused);
        }

        deliveries.save(EmailDelivery.accepted(
                message.id(),
                message.recipientId(),
                message.type(),
                message.attempt(),
                email.subject(),
                messageId,
                now()));
    }

    @Override
    public void send(NotificationDigest digest) {
        NotificationMessage first = digest.notifications().get(0);
        UserAccount recipient = addressFor(digest.recipientId())
                .orElseThrow(() -> suppress(digest, first.attempt()));

        Locale locale = ReaderLocale.of(recipient.locale());
        RenderedEmail email = renderer.render(composer.compose(digest, recipient.name(), locale), locale);
        String messageId = mime.messageIdFor(digest.id());

        try {
            mime.send(mime.build(recipient.email().value(), recipient.name(), email, messageId));
        } catch (MailException | MessagingException refused) {
            deliveries.save(EmailDelivery.digestNotSent(
                    digest.id(),
                    digest.recipientId(),
                    digest.notifications().size(),
                    EmailDeliveryOutcome.REFUSED,
                    first.attempt(),
                    email.subject(),
                    messageId,
                    describe(refused)));
            throw refused instanceof RuntimeException unchecked
                    ? unchecked
                    : new MailDeliveryFailedException(refused);
        }

        deliveries.save(EmailDelivery.digestAccepted(
                digest.id(),
                digest.recipientId(),
                digest.notifications().size(),
                first.attempt(),
                email.subject(),
                messageId,
                now()));
    }

    /**
     * The account behind a recipient identifier, when it still has somewhere to write to.
     *
     * <p>Empty covers both ways there can be nothing: no account, and an account whose
     * address anonymisation has replaced. Neither is recoverable and the caller treats
     * them the same, so they are not distinguished here — but they are distinguished in
     * what gets recorded, which is where the difference is worth having.
     */
    private Optional<UserAccount> addressFor(UUID recipientId) {
        return users.findById(recipientId)
                .filter(account -> !account.email().value().endsWith(UNDELIVERABLE_SUFFIX));
    }

    /** Records the suppression and produces the exception that dead-letters the row. */
    private PermanentDeliveryFailure suppress(NotificationMessage message) {
        String reason = reasonFor(message.recipientId());
        deliveries.save(EmailDelivery.notSent(
                message.id(),
                message.recipientId(),
                message.type(),
                EmailDeliveryOutcome.SUPPRESSED,
                message.attempt(),
                // Nothing was rendered: the decision not to send is taken before the
                // template is, because rendering a message for nobody is work whose only
                // product is a subject line in a table.
                null,
                null,
                reason));
        log.info("An email for {} was suppressed: {}", message, reason);
        return new PermanentDeliveryFailure(reason);
    }

    /** The same, for a digest. */
    private PermanentDeliveryFailure suppress(NotificationDigest digest, int attempt) {
        String reason = reasonFor(digest.recipientId());
        deliveries.save(EmailDelivery.digestNotSent(
                digest.id(),
                digest.recipientId(),
                digest.notifications().size(),
                EmailDeliveryOutcome.SUPPRESSED,
                attempt,
                null,
                null,
                reason));
        log.info("A digest for {} was suppressed: {}", digest, reason);
        return new PermanentDeliveryFailure(reason);
    }

    /**
     * Why there was nowhere to send.
     *
     * <p><strong>No address appears in it.</strong> This ends up in
     * {@code notifications.last_error} and {@code email_deliveries.detail}, both read by
     * people who are not the recipient, and §17.4 keeps personal data out of both.
     */
    private String reasonFor(UUID recipientId) {
        return users.findById(recipientId).isEmpty()
                ? "The recipient is not an account"
                : "The recipient's address has been anonymised";
    }

    /**
     * The failure, as a sentence for {@code email_deliveries.detail}.
     *
     * <p>The type and the message, not the stack — {@code NotificationDispatch} makes the
     * argument and writes the same kind of column.
     */
    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private Instant now() {
        // Truncated for the reason every other clock read in this codebase is: PostgreSQL
        // stores microseconds and Java offers nanoseconds, so an untruncated instant is
        // one that does not survive a round trip and a test comparing the two fails for a
        // reason that has nothing to do with email.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A checked mail failure, as the unchecked one {@link ChannelSender} asks for.
     *
     * <p>{@code MessagingException} is checked and the port's {@code send} declares
     * nothing, which is correct — a channel's failure mode is not the caller's to
     * enumerate. Wrapping is how it crosses.
     */
    static final class MailDeliveryFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MailDeliveryFailedException(Throwable cause) {
            super("The relay did not accept the message", cause);
        }
    }
}
