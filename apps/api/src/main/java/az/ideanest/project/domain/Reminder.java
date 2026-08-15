package az.ideanest.project.domain;

import az.ideanest.shared.EmailAddress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Somebody who asked to be told when a campaign opens.
 *
 * <p><strong>One row is one identity, and there are two kinds of identity.</strong>
 * A signed-in account is held as {@link #getUserId()} and its address is not
 * copied here — the sender reads it from {@code users} at the moment it sends, so
 * §17.4 anonymisation reaches it without this table having to be swept. Somebody
 * with no account is held as {@link #getEmail()}, because there is nowhere else
 * their address could live. Exactly one of the two is set;
 * {@code reminders_one_identity} refuses anything else, and
 * {@link #isForAccount()} is how the rest of the code asks which kind it is
 * looking at.
 *
 * <p><strong>There is no {@code setNotifiedAt}.</strong> The stamp is written by
 * {@code ReminderRepository#claim}, a conditional update, and never by loading a
 * row and assigning to it: two sweeps running at once — which is the normal state
 * of affairs on more than one replica — would both read {@code null}, both decide
 * the person had not been told, and both send. Only the database can break that
 * tie, and it can only break it if the condition is part of the statement.
 *
 * <p>The project and the account are identifiers rather than associations, for
 * the reason given on {@link Project}: {@code users} belongs to another module and
 * a {@code @ManyToOne} across that boundary is the coupling
 * {@code ModuleBoundaryTests} exists to prevent.
 */
@Entity
@Table(name = "reminders")
public class Reminder {

    /** SHA-256, as {@code reminders_token_hash_is_sha256} also requires. */
    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null on a reminder registered by somebody with no account. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /**
     * The address to write to, on a reminder registered by somebody with no
     * account. Null on an account's reminder, whose address is read from
     * {@code users} instead.
     *
     * <p>{@code citext} named explicitly, as {@code collaborators.invited_email}
     * is: without the column definition Hibernate expects a {@code varchar} and
     * refuses to start against this schema.
     */
    @Column(name = "email", updatable = false, columnDefinition = "citext")
    private EmailAddress email;

    /**
     * The stored form of the "stop reminding me" token, or null while there is no
     * message for it to be in.
     *
     * <p>Written by the same statement that claims the row for sending, because
     * the link and the message that carries it are one fact. See the column comment
     * in {@code V10} for why it is not minted at registration.
     */
    @Column(name = "unsubscribe_token_hash")
    private byte[] unsubscribeTokenHash;

    /**
     * When the launch notice was handed to the sender, or null when it has not
     * been.
     *
     * <p>There is no setter, and nothing in the application assigns to this field:
     * the only correct way to write it is the conditional update described on the
     * class, so the field exists to be read back after that statement has run.
     */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Reminder() {
        // JPA.
    }

    /** Whether this reminder belongs to an account rather than to a bare address. */
    public boolean isForAccount() {
        return userId != null;
    }

    /** Whether the launch notice for this row has already been handed to the sender. */
    public boolean isNotified() {
        return notifiedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getUserId() {
        return userId;
    }

    public EmailAddress getEmail() {
        return email;
    }

    public byte[] getUnsubscribeTokenHash() {
        // A defensive copy. The array is the stored form of a credential, and an
        // accessor that hands out the live array lets a caller mutate the row's
        // identity without going through a repository.
        return unsubscribeTokenHash == null ? null : unsubscribeTokenHash.clone();
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
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
        return other instanceof Reminder reminder && Objects.equals(id, reminder.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No address and no account: this lands in logs, and §17.4 keeps both out
        // of them. Whether it has been notified is the thing a log line about a
        // reminder is almost always asking about.
        return "Reminder[id=" + id + ", project=" + projectId + ", notified=" + isNotified() + "]";
    }
}
