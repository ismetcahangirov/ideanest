package az.ideanest.project.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * One earlier draft of a campaign's story.
 *
 * <p><strong>Immutable once written.</strong> There is no setter on this class at
 * all, and that is the design: a version whose document could be edited would
 * make the history a record of what somebody later decided the past should have
 * been. Restoring an old version writes the current story and, when it is due,
 * adds a new version — it never rewrites the one being restored from.
 *
 * <p>The document is held as text against a {@code jsonb} column, as
 * {@link Project#getStory()} is and for the same reason: the entity has no need
 * to understand the document, and parsing it on every row of a version list
 * would be work done to produce a number the list could have been given.
 */
@Entity
@Table(name = "project_story_versions")
public class StoryVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, updatable = false)
    private String document;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    /**
     * The database's, through a default, so that a version cannot claim to have
     * been written at a time the application chose. {@link Generated} is what
     * makes it readable in the request that wrote it — without it the response to
     * a save would report a null timestamp for a row that plainly has one.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected StoryVersion() {
        // JPA.
    }

    private StoryVersion(UUID projectId, int versionNumber, String document, UUID authorId) {
        this.id = Identifiers.newIdentifier();
        this.projectId = projectId;
        this.versionNumber = versionNumber;
        this.document = document;
        this.authorId = authorId;
    }

    /**
     * Records a draft.
     *
     * @param versionNumber allocated by {@code StoryVersionService} as one past
     *     the newest. Passed in rather than computed here because the entity
     *     cannot see the other rows, and a number invented locally is the kind of
     *     thing that quietly becomes a duplicate
     * @param document the story as JSON text, already validated by
     *     {@link StoryDocuments}
     * @param authorId who was editing. #38 makes a collaborator's draft
     *     distinguishable from the creator's in the list
     */
    public static StoryVersion record(UUID projectId, int versionNumber, String document, UUID authorId) {
        Objects.requireNonNull(projectId, "A story version belongs to a project");
        Objects.requireNonNull(document, "A story version holds a document");
        Objects.requireNonNull(authorId, "A story version names who wrote it");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Story versions are numbered from 1");
        }
        return new StoryVersion(projectId, versionNumber, document, authorId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getDocument() {
        return document;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof StoryVersion version && Objects.equals(id, version.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No document: an unlaunched campaign's story is confidential, and this
        // lands in logs.
        return "StoryVersion[project=" + projectId + ", number=" + versionNumber + "]";
    }
}
