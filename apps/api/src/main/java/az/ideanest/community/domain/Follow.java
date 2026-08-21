package az.ideanest.community.domain;

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
 * One account following another — §4.9's C-10.
 *
 * <p>The mirror of {@link Save} and everything said there applies: no states, nothing edits it,
 * unfollowing deletes the row, and registration is an {@code ON CONFLICT DO NOTHING} insert in
 * the repository rather than a constructor here.
 *
 * <p><strong>The two ends are not interchangeable.</strong> {@code creatorId} is who is being
 * followed and {@code followerId} is who is doing it, and swapping them at a call site produces
 * a row the database happily accepts and which means the opposite thing. That is why they are
 * named for their roles rather than {@code userId} and {@code otherUserId}, and why
 * {@code follows_is_not_self} is a constraint: the one pair the mistake cannot produce is the
 * one where both ends are the same account, so the check catches the narrow case where the
 * error is provable and the naming has to catch the rest.
 */
@Entity
@Table(name = "follows")
public class Follow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The account being followed. Any account, not only one that has launched a campaign. */
    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    /** The account doing the following. */
    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Follow() {
        // JPA.
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public UUID getFollowerId() {
        return followerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Follow follow && Objects.equals(id, follow.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Follow[id=" + id + ", creator=" + creatorId + ", follower=" + followerId + "]";
    }
}
