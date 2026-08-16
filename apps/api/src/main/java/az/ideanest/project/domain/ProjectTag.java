package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One campaign carrying one tag.
 *
 * <p>An edge and nothing else: it has no properties of its own, because
 * everything a tag says lives on {@link Tag} and everything a campaign says
 * lives on {@link Project}. The pair is the identity — tagging a campaign with
 * the same word twice is not a stronger claim about it, and a second row would
 * make the tag count double.
 *
 * <p><strong>How many tags a campaign may carry is not enforced here.</strong>
 * "At most N rows with this project_id" is a statement about sibling rows, which
 * no per-row constraint can see; V11 sets out why the alternative — a counting
 * trigger — was rejected, and the short version is that a limit exceeded in a
 * trigger reaches the client as a 500 while a limit enforced in a service
 * reaches it as a 400 naming the field. That service is the campaign editor's
 * tag field, which does not exist yet.
 */
@Entity
@Table(name = "project_tags")
public class ProjectTag {

    /** The campaign and the tag. See {@code CategoryTranslation.Key} for why it is a class. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        @Column(name = "tag_id", nullable = false, updatable = false)
        private UUID tagId;

        protected Key() {
            // JPA.
        }

        public Key(UUID projectId, UUID tagId) {
            this.projectId = Objects.requireNonNull(projectId, "A tag edge names its campaign");
            this.tagId = Objects.requireNonNull(tagId, "A tag edge names its tag");
        }

        public UUID getProjectId() {
            return projectId;
        }

        public UUID getTagId() {
            return tagId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(projectId, key.projectId)
                    && Objects.equals(tagId, key.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, tagId);
        }

        @Override
        public String toString() {
            return "Key[project=" + projectId + ", tag=" + tagId + "]";
        }
    }

    @EmbeddedId
    private Key id;

    protected ProjectTag() {
        // JPA.
    }

    public static ProjectTag of(UUID projectId, UUID tagId) {
        ProjectTag edge = new ProjectTag();
        edge.id = new Key(projectId, tagId);
        return edge;
    }

    public UUID getProjectId() {
        return id.getProjectId();
    }

    public UUID getTagId() {
        return id.getTagId();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ProjectTag edge && Objects.equals(id, edge.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ProjectTag[" + id + "]";
    }
}
