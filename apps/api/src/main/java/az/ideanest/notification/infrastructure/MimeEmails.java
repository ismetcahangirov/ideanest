package az.ideanest.notification.infrastructure;

import az.ideanest.notification.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * A {@link RenderedEmail} as MIME, and the one place the envelope is decided.
 *
 * <p>Two callers build messages: {@code EmailChannelSender}, which sends what the queue
 * owes people, and {@code EmailTemplates}, which sends a staff member a preview of one.
 * They differ in what they send and in what they record, and in nothing else — so the
 * {@code From}, the character set, the multipart ordering and the {@code Message-ID}
 * live here rather than in both. A test send that differed from a real one in any of
 * those would be a preview of something the platform does not send.
 */
@Component
public class MimeEmails {

    private final JavaMailSender mail;
    private final NotificationProperties properties;

    public MimeEmails(JavaMailSender mail, NotificationProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    /**
     * The message, ready to hand to the relay.
     *
     * <p><strong>{@code multipart/alternative}, plain text first.</strong> That order is
     * the standard's and it carries meaning: a client shows the last part it understands,
     * so text first and HTML second is what makes an HTML-capable client show the HTML
     * and a plain-text one show something readable. {@link MimeMessageHelper#setText}
     * writes them in that order when handed the text first, which is why the argument
     * order below is not arbitrary.
     *
     * @param messageId the RFC 5322 identifier this message carries, in angle brackets
     */
    public MimeMessage build(String toAddress, String toName, RenderedEmail email, String messageId)
            throws MessagingException {

        MimeMessage mime = mail.createMimeMessage();

        MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
        helper.setFrom(address(properties.email().from(), properties.email().fromName()));
        if (properties.email().replyTo() != null) {
            helper.setReplyTo(properties.email().replyTo());
        }
        helper.setTo(address(toAddress, toName));
        helper.setSubject(email.subject());
        helper.setText(email.text(), email.html());

        // JavaMail generates its own Message-ID inside saveChanges(), which would discard
        // this one and with it the deduplication EmailChannelSender depends on.
        // JavaMailSenderImpl.doSend reads the header before calling saveChanges() and
        // writes it back afterwards, precisely so that an explicitly set identifier
        // survives — so this works because Spring sends the message, not because the
        // header was set last. A transport calling Transport.send(mime) directly would
        // need saveChanges() first and this line after it.
        mime.setHeader("Message-ID", messageId);
        return mime;
    }

    /** Hands it over. Throws exactly what {@link JavaMailSender} throws. */
    public void send(MimeMessage message) {
        mail.send(message);
    }

    /**
     * The {@code Message-ID} for a notification or digest, derived rather than generated.
     *
     * <p>{@code <identifier@sending-domain>}. The left-hand side is the identifier
     * {@code ChannelSender} guarantees is stable across every attempt, which is the whole
     * point of deriving it; the right-hand side comes from the {@code From} address, so
     * the two cannot disagree.
     */
    public String messageIdFor(UUID id) {
        return "<" + id + "@" + properties.email().messageIdDomain() + ">";
    }

    /**
     * A named address, encoded so that a name outside ASCII survives the header.
     *
     * <p>{@code UnsupportedEncodingException} is checked and cannot happen: every JVM is
     * required to support UTF-8, and it is named from {@link StandardCharsets} rather
     * than as a string somebody could mistype. Rethrown as unchecked rather than
     * declared, because propagating it would put a charset question in the signature of
     * every method between here and the port.
     */
    private static InternetAddress address(String value, String name) throws MessagingException {
        try {
            return new InternetAddress(value, name, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("This JVM does not support UTF-8", impossible);
        }
    }
}
