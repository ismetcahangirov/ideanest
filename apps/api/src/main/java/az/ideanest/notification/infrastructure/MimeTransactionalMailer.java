package az.ideanest.notification.infrastructure;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.TransactionalMail;
import az.ideanest.notification.application.TransactionalMailFailedException;
import az.ideanest.notification.application.TransactionalMailer;
import jakarta.mail.MessagingException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

/**
 * {@link TransactionalMailer} over #86's existing transport.
 *
 * <p>Every part of an email that is not its words already exists: {@link EmailRenderer}
 * owns the two layouts, {@link MimeEmails} owns the {@code From}, the character set, the
 * multipart ordering and the {@code Message-ID}. This class is the mapping between the
 * published record and {@link EmailContent} and nothing else — which is the point, because
 * a second class that built its own MIME message would be a second envelope to keep in
 * step, and the first thing to drift would be the sender address.
 *
 * <h2>The footer loses a line</h2>
 *
 * <p>{@code EmailRenderer} normally ends a message with "you can change which emails you
 * get, and how often, in your notification settings". <strong>That sentence is false on
 * every message this class sends.</strong> A verification link, a password reset and a
 * change-of-address notice are not preference-controlled and must not be: an account
 * whose owner had switched off "password was changed" is an account takeover nobody is
 * told about. So the line is omitted rather than shown and quietly disregarded — see
 * {@link EmailRenderer#render(EmailContent, Locale, boolean)}.
 *
 * <h2>The Message-ID is random, and says so</h2>
 *
 * <p>{@code EmailChannelSender} derives its identifier from the notification, so a
 * message the at-least-once queue hands over twice carries one identifier and conforming
 * clients collapse the copies. There is no queue here and no row to derive from, so the
 * identifier is generated. That is not a weaker version of the same property; it is the
 * honest statement that this path has no deduplication, and it will not have one before
 * #135 gives these messages an outbox row to be named after.
 */
@Component
public class MimeTransactionalMailer implements TransactionalMailer {

    private static final Logger log = LoggerFactory.getLogger(MimeTransactionalMailer.class);

    private final MimeEmails mime;
    private final EmailRenderer renderer;
    private final NotificationProperties properties;

    public MimeTransactionalMailer(MimeEmails mime, EmailRenderer renderer, NotificationProperties properties) {
        this.mime = mime;
        this.renderer = renderer;
        this.properties = properties;
    }

    @Override
    public void send(String toAddress, String toName, TransactionalMail mail, Locale locale) {
        RenderedEmail email = renderer.render(contentOf(mail), locale, false);
        String messageId = mime.messageIdFor(UUID.randomUUID());

        try {
            mime.send(mime.build(toAddress, toName, email, messageId));
        } catch (MailException | MessagingException refused) {
            throw new TransactionalMailFailedException("The relay refused a transactional email", refused);
        }

        // No email_deliveries row: that table's rows point at a notification and this
        // message is not one. A log line is what EmailTemplates.testSend settled for in
        // the same position, and for the same reason.
        log.info("Transactional email accepted by the relay: {}", messageId);
    }

    /**
     * The published record as the renderer's own type.
     *
     * <p>The only decision taken here is the origin, and it is taken here rather than by
     * the caller so that one deployment cannot send readers to another's front end.
     */
    private EmailContent contentOf(TransactionalMail mail) {
        String actionUrl = mail.actionPath() == null ? null : properties.email().baseUrl() + mail.actionPath();
        return EmailContent.of(
                mail.subject(), mail.headline(), List.copyOf(mail.paragraphs()), mail.actionLabel(), actionUrl);
    }
}
