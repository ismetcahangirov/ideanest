package az.ideanest.community.domain;

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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One numbered post a creator published to a campaign. §4.4's Updates tab, §4.7's
 * CD-12.
 *
 * <p><strong>Immutable once written.</strong> There is no setter on this class, and
 * that follows from §10.2 rather than from taste: the specification gives an update a
 * create endpoint and a read endpoint and nothing else. An update is a statement made
 * to people who have already read it — §5.5 makes publishing them an obligation, and
 * an obligation that can be edited afterwards is a weaker one. When AD-09's content
 * moderation of updates arrives it withdraws a row rather than rewriting it, for the
 * same reason {@code project_state_transitions} is append-only.
 *
 * <p><strong>{@link #getNumber()} is allocated by the service, not here.</strong> The
 * entity cannot see the other rows, and a number invented locally is the kind of thing
 * that quietly becomes a duplicate. {@code project_updates_number_key} is what decides
 * a race between two writers.
 *
 * <p><strong>{@link #getPublishedAt()} may be in the future.</strong> That is the whole
 * of "scheduled" — see V22 for why there is no state column and no job.
 */
@Entity
@Table(name = "project_updates")
public class ProjectUpdate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, updatable = false)
    private UpdateVisibility visibility;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    /**
     * The database's, through a default, so an update cannot claim to have been
     * written at a time the application chose. {@link Generated} is what makes it
     * readable in the request that wrote it — without it the response to a publish
     * would report a null timestamp for a row that plainly has one.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProjectUpdate() {
        // JPA.
    }

    private ProjectUpdate(
            UUID projectId,
            int number,
            String title,
            String body,
            UpdateVisibility visibility,
            UUID authorId,
            Instant publishedAt) {

        this.id = Identifiers.newIdentifier();
        this.projectId = projectId;
        this.number = number;
        this.title = title;
        this.body = body;
        this.visibility = visibility;
        this.authorId = authorId;
        this.publishedAt = publishedAt;
    }

    /**
     * Writes an update.
     *
     * <p>The title and the body go through {@link UpdateContent} here rather than at
     * the edge, so that there is no way to construct one that the rules have not seen
     * — a second write path added later inherits the check instead of having to
     * remember it.
     *
     * @param number allocated by {@code ProjectUpdateService} as one past the newest.
     *     See the class comment for why it is not computed here
     * @param authorId who published it: the authenticated caller, never a value from a
     *     request body
     * @param publishedAt when it becomes readable. Now, or a moment in the future for
     *     a scheduled update
     */
    public static ProjectUpdate publish(
            UUID projectId,
            int number,
            String title,
            String body,
            UpdateVisibility visibility,
            UUID authorId,
            Instant publishedAt) {

        Objects.requireNonNull(projectId, "An update belongs to a campaign");
        Objects.requireNonNull(visibility, "An update says who it is for");
        Objects.requireNonNull(authorId, "An update names who published it");
        Objects.requireNonNull(publishedAt, "An update says when it becomes readable");
        if (number < 1) {
            throw new IllegalArgumentException("Updates are numbered from 1");
        }
        return new ProjectUpdate(
                projectId,
                number,
                UpdateContent.title(title),
                UpdateContent.body(body),
                visibility,
                authorId,
                publishedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UpdateVisibility getVisibility() {
        return visibility;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Whether this update has become readable, as of {@code now}. */
    public boolean isPublishedAsOf(Instant now) {
        return !publishedAt.isAfter(now);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ProjectUpdate update && Objects.equals(id, update.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No title and no body: a scheduled update is an announcement nobody has
        // been given yet, and this lands in logs.
        return "ProjectUpdate[project=" + projectId + ", number=" + number + "]";
    }
}
