package az.ideanest.notification.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing the platform owes one person on one channel.
 *
 * <p>Written by {@code NotificationFanOut} inside the outbox dispatch transaction, so
 * that it exists exactly when the event that caused it is recorded as delivered — and
 * then sent afterwards by {@code NotificationDispatch}, because a sent email is not
 * rolled back.
 *
 * <h2>The lifecycle, and the two ways it ends</h2>
 *
 * <p>{@code PENDING → SENT}, or {@code PENDING → DEAD} once the attempts are spent.
 * {@code HELD} is a third starting state rather than a step on the way: a digest is
 * held from the moment it is written, and {@code notification-digest} moves it —
 * {@code HELD → SENT} for a group that was combined and sent, {@code HELD → DEAD} once its
 * attempts are spent. {@link NotificationState#HELD} and {@code DigestAssembly} carry the
 * detail.
 *
 * <h2>What is mutable, and what is not</h2>
 *
 * <p>Everything describing the notification is {@code updatable = false}: a message
 * whose recipient, type, or contents could be edited after the fact is a statement
 * somebody was told that is no longer what they were told. What moves is the delivery
 * — the state, the attempt count, the error, the timestamps — and
 * {@link #markRead(Instant)}, which is the recipient's own act.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    /**
     * Stored rather than derived from {@link #type} at read time.
     *
     * <p>A notification keeps the grouping it was sent under. Moving a type between
     * categories later is a product decision about future notifications, and it must
     * not silently rewrite what somebody was already told.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    /**
     * The outbox event this was fanned out from.
     *
     * <p>With the recipient and the channel it is the row's uniqueness, which is how
     * this consumer honours {@code OutboxMessage}'s at-least-once contract.
     */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "subject_type", updatable = false)
    private String subjectType;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    /**
     * What a renderer needs and cannot look up, as a JSON object.
     *
     * <p>Mapped as a string and stored as {@code jsonb}, the way {@code Project.story}
     * is: this module does not read inside the document, and the template that will is
     * #86's. Money in here is §10.3's {@code {"amount", "currency"}} object with the
     * amount as a string — the fan-out serialises it with the application's own
     * {@code ObjectMapper}, so {@code MoneySerializer}'s rule applies without this class
     * knowing that is what it is asking for.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, updatable = false)
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private NotificationState state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /**
     * When the reported thing happened, from the event rather than from the clock.
     *
     * <p>An event redelivered an hour late still describes something that happened an
     * hour ago, and an inbox ordered by when the row was written would put it in the
     * wrong place.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // JPA.
    }

    /**
     * A notification about to be sent.
     *
     * @param eventId the outbox delivery this came from, stable across every redelivery
     * @param params the rendering document, already serialised. Never null: the schema
     *     defaults it to {@code {}} and a column that is sometimes absent is a template
     *     that has to test for it
     * @param at the instant it becomes eligible, which is now — the first attempt waits
     *     for nothing
     */
    public static Notification pending(
            UUID recipientId,
            NotificationType type,
            NotificationChannel channel,
            UUID eventId,
            String subjectType,
            UUID subjectId,
            String params,
            Instant occurredAt,
            Instant at) {

        Notification notification =
                describe(recipientId, type, channel, eventId, subjectType, subjectId, params, occurredAt);
        notification.state = NotificationState.PENDING;
        notification.nextAttemptAt = Objects.requireNonNull(at, "A notification is eligible from some moment");
        return notification;
    }

    /**
     * A notification held for a digest.
     *
     * <p>{@code next_attempt_at} is still written, and still to now. V26 called it "the column
     * a combining job would order by"; what {@code DigestAssembly} actually needs it for is the
     * backoff — a digest a channel refuses moves every row in it to the same next attempt — and
     * a held row with no eligibility instant would be one that job could not judge.
     */
    public static Notification held(
            UUID recipientId,
            NotificationType type,
            NotificationChannel channel,
            UUID eventId,
            String subjectType,
            UUID subjectId,
            String params,
            Instant occurredAt,
            Instant at) {

        if (!channel.isDigestible()) {
            // Also refused by notifications_in_app_is_not_held. Here as well, because
            // the failure a person would see is "I chose digest and never heard
            // anything", and it should be impossible to construct rather than merely
            // impossible to store.
            throw new IllegalArgumentException("A " + channel + " notification is never held for a digest");
        }
        Notification notification =
                describe(recipientId, type, channel, eventId, subjectType, subjectId, params, occurredAt);
        notification.state = NotificationState.HELD;
        notification.nextAttemptAt = Objects.requireNonNull(at, "A held notification is ordered by some moment");
        return notification;
    }

    private static Notification describe(
            UUID recipientId,
            NotificationType type,
            NotificationChannel channel,
            UUID eventId,
            String subjectType,
            UUID subjectId,
            String params,
            Instant occurredAt) {

        Notification notification = new Notification();
        notification.id = Identifiers.newIdentifier();
        notification.recipientId = Objects.requireNonNull(recipientId, "A notification is told to somebody");
        notification.type = Objects.requireNonNull(type, "A notification is of some kind");
        notification.category = type.category();
        notification.channel = Objects.requireNonNull(channel, "A notification goes somewhere");
        if (!type.supports(channel)) {
            // §4.10's crosses are channels the type does not have. The fan-out never
            // asks for one; this refuses the caller that does.
            throw new IllegalArgumentException("§4.10 gives " + type + " no " + channel + " channel");
        }
        notification.eventId = Objects.requireNonNull(eventId, "A notification names the delivery it came from");

        if ((subjectType == null) != (subjectId == null)) {
            // notifications_subject_is_whole. Half a reference is one nothing can follow.
            throw new IllegalArgumentException("A subject is a type and an identifier, or it is neither");
        }
        notification.subjectType = subjectType;
        notification.subjectId = subjectId;

        notification.params = Objects.requireNonNull(params, "A notification carries a rendering document");
        notification.occurredAt = Objects.requireNonNull(occurredAt, "Something happened at some moment");
        notification.attempts = 0;
        return notification;
    }

    /**
     * The channel accepted it.
     *
     * <p>Not "the recipient read it", and for email and push not even "it arrived": it
     * means the transport took it, which is the strongest thing that can honestly be
     * recorded on this side of a provider.
     */
    public void sent(Instant at) {
        this.state = NotificationState.SENT;
        this.sentAt = Objects.requireNonNull(at, "A notification was sent at some moment");
    }

    /** The channel refused it, and there is another attempt. */
    public void retryAfter(Instant now, Duration backoff, String reason) {
        this.attempts += 1;
        this.lastError = reason;
        this.nextAttemptAt = now.plus(backoff);
    }

    /** The channel refused it for the last time. */
    public void deadLetter(Instant now, String reason) {
        this.attempts += 1;
        this.lastError = Objects.requireNonNull(reason, "A dead letter says why");
        this.state = NotificationState.DEAD;
        this.nextAttemptAt = now;
    }

    /**
     * The recipient opened it.
     *
     * <p>Idempotent: the first time is the one recorded. A second call would move the
     * instant to whenever the client last re-rendered the list, which is not when
     * anybody read anything.
     */
    public void markRead(Instant at) {
        if (readAt == null) {
            this.readAt = Objects.requireNonNull(at, "A notification was read at some moment");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationCategory getCategory() {
        return category;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getParams() {
        return params;
    }

    public NotificationState getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Notification notification && Objects.equals(id, notification.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No recipient and no params. This ends up in log lines about failed sends,
        // and both of those are one person's business (§17.4).
        return "Notification[id=" + id + ", type=" + type + ", channel=" + channel + ", state=" + state + "]";
    }
}
