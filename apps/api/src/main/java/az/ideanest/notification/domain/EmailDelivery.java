package az.ideanest.notification.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt by the email transport, as a row that is written once and never changed.
 *
 * <p>{@link Notification} already holds the <em>current</em> state of a message — its
 * attempt count, its last error, whether it is a dead letter. What it cannot hold is the
 * history, and the history is what somebody needs when a backer says they were never
 * told a payment failed. So this is append-only: a notification retried eight times has
 * eight rows here and the last one is its outcome.
 *
 * <p>Every field is {@code updatable = false} and there is no mutator on the class. That
 * is not caution, it is the type: an attempt is something that happened, and a record of
 * what happened that can be edited afterwards is not a record.
 *
 * <h2>What is not on it</h2>
 *
 * <p><strong>There is no address.</strong> V30's header makes the argument in full and
 * the short version is §17.4: anonymisation rewrites {@code users.email}, and an address
 * copied here would outlive that in a table the anonymiser does not know about.
 * {@link #recipientId} resolves to the current address through {@code users} for exactly
 * as long as there is a person to resolve it to, which is the correct lifetime.
 *
 * <p><strong>There is no delivery.</strong> {@link EmailDeliveryOutcome} says why.
 */
@Entity
@Table(name = "email_deliveries")
public class EmailDelivery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The notification this was for, or null when it was a digest. */
    @Column(name = "notification_id", updatable = false)
    private UUID notificationId;

    /**
     * The digest this was for, or null when it was a single message.
     *
     * <p>Not a foreign key and not an entity reference: a digest is a value derived from
     * the set of held notifications combined into it, not a row anywhere. See
     * {@code NotificationDigest.of}.
     */
    @Column(name = "digest_id", updatable = false)
    private UUID digestId;

    @Column(name = "member_count", nullable = false, updatable = false)
    private int memberCount;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    /** Null for a digest, which is several types at once. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private EmailDeliveryOutcome outcome;

    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt;

    /** Both null on a suppressed attempt: the decision not to send precedes rendering. */
    @Column(name = "subject", updatable = false)
    private String subject;

    @Column(name = "message_id", updatable = false)
    private String messageId;

    /** Why, for the two outcomes that have a why. Null on {@code ACCEPTED}. */
    @Column(name = "detail", updatable = false)
    private String detail;

    @Column(name = "accepted_at", updatable = false)
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected EmailDelivery() {
        // JPA.
    }

    /**
     * The relay took a single message.
     *
     * @param at when it took it. Passed in rather than read from a clock here, so that
     *     every row one dispatch writes carries the moment that dispatch was judged
     *     against — the same reason {@code NotificationDispatch.sendNext} takes one
     */
    public static EmailDelivery accepted(
            UUID notificationId,
            UUID recipientId,
            NotificationType type,
            int attempt,
            String subject,
            String messageId,
            Instant at) {

        EmailDelivery delivery = of(recipientId, attempt);
        delivery.notificationId = Objects.requireNonNull(notificationId, "A single message is about a notification");
        delivery.type = Objects.requireNonNull(type, "A single message is of some type");
        delivery.outcome = EmailDeliveryOutcome.ACCEPTED;
        delivery.subject = required(subject, "An accepted message went out with a subject");
        delivery.messageId = required(messageId, "An accepted message went out with a Message-ID");
        delivery.acceptedAt = Objects.requireNonNull(at, "An acceptance happened at some moment");
        return delivery;
    }

    /** The relay took a digest, covering {@code memberCount} notifications. */
    public static EmailDelivery digestAccepted(
            UUID digestId,
            UUID recipientId,
            int memberCount,
            int attempt,
            String subject,
            String messageId,
            Instant at) {

        EmailDelivery delivery = of(recipientId, attempt);
        delivery.digestId = Objects.requireNonNull(digestId, "A digest has a key");
        delivery.memberCount = atLeastOne(memberCount, "A digest covers at least one notification");
        delivery.outcome = EmailDeliveryOutcome.ACCEPTED;
        delivery.subject = required(subject, "An accepted digest went out with a subject");
        delivery.messageId = required(messageId, "An accepted digest went out with a Message-ID");
        delivery.acceptedAt = Objects.requireNonNull(at, "An acceptance happened at some moment");
        return delivery;
    }

    /**
     * The relay would not take a single message, or the platform would not build one.
     *
     * @param outcome {@link EmailDeliveryOutcome#REFUSED} when the relay said no and the
     *     attempt is worth repeating, {@link EmailDeliveryOutcome#SUPPRESSED} when there
     *     was nothing to send to. The two differ in what happens next and the caller is
     *     the only side that knows which it is
     * @param subject what was going to go out, or null when nothing was rendered — which
     *     is every suppression, because the decision is taken before the template is
     */
    public static EmailDelivery notSent(
            UUID notificationId,
            UUID recipientId,
            NotificationType type,
            EmailDeliveryOutcome outcome,
            int attempt,
            String subject,
            String messageId,
            String detail) {

        EmailDelivery delivery = of(recipientId, attempt);
        delivery.notificationId = Objects.requireNonNull(notificationId, "A single message is about a notification");
        delivery.type = Objects.requireNonNull(type, "A single message is of some type");
        delivery.outcome = refusalOrSuppression(outcome);
        delivery.subject = subject;
        delivery.messageId = messageId;
        delivery.detail = required(detail, "A message that did not go says why");
        return delivery;
    }

    /** The same, for a digest. */
    public static EmailDelivery digestNotSent(
            UUID digestId,
            UUID recipientId,
            int memberCount,
            EmailDeliveryOutcome outcome,
            int attempt,
            String subject,
            String messageId,
            String detail) {

        EmailDelivery delivery = of(recipientId, attempt);
        delivery.digestId = Objects.requireNonNull(digestId, "A digest has a key");
        delivery.memberCount = atLeastOne(memberCount, "A digest covers at least one notification");
        delivery.outcome = refusalOrSuppression(outcome);
        delivery.subject = subject;
        delivery.messageId = messageId;
        delivery.detail = required(detail, "A digest that did not go says why");
        return delivery;
    }

    private static EmailDelivery of(UUID recipientId, int attempt) {
        EmailDelivery delivery = new EmailDelivery();
        delivery.id = Identifiers.newIdentifier();
        delivery.recipientId = Objects.requireNonNull(recipientId, "An attempt was made to reach somebody");
        delivery.attempt = atLeastOne(attempt, "Attempts are counted from one");
        delivery.memberCount = 1;
        return delivery;
    }

    private static EmailDeliveryOutcome refusalOrSuppression(EmailDeliveryOutcome outcome) {
        Objects.requireNonNull(outcome, "An attempt that did not go out ended some way");
        if (outcome == EmailDeliveryOutcome.ACCEPTED) {
            // Refused here rather than in the database, so that the failure names the
            // caller that built the row instead of the insert that rejected it.
            throw new IllegalArgumentException("A message that was not sent was not accepted");
        }
        return outcome;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static int atLeastOne(int value, String message) {
        if (value < 1) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getDigestId() {
        return digestId;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public NotificationType getType() {
        return type;
    }

    public EmailDeliveryOutcome getOutcome() {
        return outcome;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getSubject() {
        return subject;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        // No recipient, no subject and no detail: a subject line is one person's
        // business (§17.4) and this ends up in log lines about failed sends.
        return "EmailDelivery[id=" + id + ", outcome=" + outcome + ", attempt=" + attempt + "]";
    }
}
