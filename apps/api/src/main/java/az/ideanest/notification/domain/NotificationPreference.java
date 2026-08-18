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
 * One standing instruction: what this person wants on this category, on this channel.
 *
 * <p><strong>A row exists only because somebody said something.</strong> There is no
 * seed and no registration hook that writes these — {@link DeliveryPolicy} explains
 * why at length. The consequence to keep in mind when reading any query against this
 * table is that the common case is <em>no row</em>, and that absence is meaningful
 * rather than missing.
 *
 * <p>Mutable, unlike most of the entities in this service, and it should be: an
 * instruction is a current state rather than a record of the past. What was set
 * before is not kept — a history of somebody's notification settings is a log of
 * their attention with no retention policy anybody has argued for, and nothing in
 * §4.10 or §17.4 asks for one.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    /** The only mutable column, which is the point of the row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false)
    private DeliveryMode deliveryMode;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPreference() {
        // JPA.
    }

    /**
     * A first instruction on one category and channel.
     *
     * @param at when it was given. From the injected {@code Clock}, like every other
     *     instant the application writes, so that a test can move it
     */
    public static NotificationPreference of(
            UUID userId, NotificationCategory category, NotificationChannel channel, DeliveryMode mode, Instant at) {

        NotificationPreference preference = new NotificationPreference();
        preference.id = Identifiers.newIdentifier();
        preference.userId = Objects.requireNonNull(userId, "A preference belongs to somebody");
        preference.category = Objects.requireNonNull(category, "A preference is about a category");
        preference.channel = Objects.requireNonNull(channel, "A preference is about a channel");
        preference.deliveryMode = Objects.requireNonNull(mode, "A preference says what to do");
        preference.updatedAt = Objects.requireNonNull(at, "An instruction was given at some moment");
        return preference;
    }

    /** Replaces the instruction. */
    public void changeTo(DeliveryMode mode, Instant at) {
        this.deliveryMode = Objects.requireNonNull(mode, "A preference says what to do");
        this.updatedAt = Objects.requireNonNull(at, "An instruction was changed at some moment");
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationCategory getCategory() {
        return category;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof NotificationPreference preference && Objects.equals(id, preference.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No user. This ends up in log lines, and "who has turned off what" is a
        // statement about a person rather than about the platform.
        return "NotificationPreference[category=" + category + ", channel=" + channel + ", mode=" + deliveryMode + "]";
    }
}
