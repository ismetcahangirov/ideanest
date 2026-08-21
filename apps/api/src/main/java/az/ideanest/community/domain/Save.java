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
 * A campaign somebody wants to come back to — §4.9's C-09.
 *
 * <p><strong>There is no behaviour here and there is not meant to be.</strong> A save is a
 * statement with two ends and a timestamp; it has no states, nothing edits it, and un-saving
 * deletes the row rather than changing it. {@code Comment} beside this one carries a tombstone
 * and a depth rule because a comment is something people argue about afterwards. This is not
 * that, and giving it a lifecycle it does not have would be inventing one for symmetry.
 *
 * <p><strong>Registration does not go through this class.</strong> {@code SaveRepository}
 * inserts with {@code ON CONFLICT DO NOTHING}, for {@code ReminderRepository}'s reason: two
 * taps arriving together would both read no row and both insert, and the database is the only
 * thing that can break that tie. So there is no {@code save(projectId, userId)} factory — a
 * constructor here would be the shape that loses the race, offered as though it were the
 * obvious way in.
 *
 * <p>Both ends are identifiers rather than associations, as everywhere else in this module:
 * {@code projects} and {@code users} belong to other modules, and a {@code @ManyToOne} across
 * that boundary is the coupling {@code ModuleBoundaryTests} exists to prevent.
 */
@Entity
@Table(name = "saves")
public class Save {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Save() {
        // JPA.
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

    /** When this campaign was saved, which is what {@code GET /v1/me/saved} orders by. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Save save && Objects.equals(id, save.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // The account is in it, unlike Reminder's, and the difference is that a save carries
        // no address: §17.4 keeps addresses out of logs, and an opaque account identifier is
        // what a support question about a missing saved campaign is asked in terms of.
        return "Save[id=" + id + ", project=" + projectId + ", account=" + userId + "]";
    }
}
