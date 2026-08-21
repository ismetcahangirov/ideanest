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
 * A message a creator sent to their backers, or to a saved segment of them — §4.7's CD-13.
 *
 * <p><strong>Immutable once written, like {@code ProjectUpdate} and for the same reason:</strong>
 * it is a statement to people who have already read it. There is no edit endpoint and no
 * withdrawal, because neither is expressible — the notifications it produced have already gone
 * to inboxes and, on the email channel, to a relay that reports nothing further.
 *
 * <p><strong>The segment is a snapshot.</strong> {@link #getSegmentId()} has no foreign key and
 * {@link #getSegmentName()} is copied in beside it; {@code V34} argues both. The short version:
 * the record has to outlive a deleted segment, and a segment's definition is editable, so a live
 * join would report this message as having gone to a set it did not go to.
 */
@Entity
@Table(name = "campaign_messages")
public class CampaignMessage {

    /** {@code campaign_messages_subject_length}, refused here so the failure names the field. */
    public static final int MAX_SUBJECT = 150;

    /**
     * {@code campaign_messages_body_length}.
     *
     * <p>Short on purpose. {@code V34} makes the argument: long-form belongs in a project update
     * (CD-12), which stores the text once and serves it from a page, whereas a message is copied
     * into the rendering document of every notification it produces — per recipient, per channel.
     */
    public static final int MAX_BODY = 2_000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null when the message went to every backer of the campaign. */
    @Column(name = "segment_id", updatable = false)
    private UUID segmentId;

    /** What that segment was called when the message went. Null exactly when the identifier is. */
    @Column(name = "segment_name", updatable = false)
    private String segmentName;

    @Column(name = "sent_by", nullable = false, updatable = false)
    private UUID sentBy;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "recipient_count", nullable = false, updatable = false)
    private int recipientCount;

    @Column(name = "truncated", nullable = false, updatable = false)
    private boolean truncated;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CampaignMessage() {
        // JPA.
    }

    private CampaignMessage(
            UUID id,
            UUID projectId,
            UUID segmentId,
            String segmentName,
            UUID sentBy,
            String subject,
            String body,
            int recipientCount,
            boolean truncated) {

        this.id = id;
        this.projectId = projectId;
        this.segmentId = segmentId;
        this.segmentName = segmentName;
        this.sentBy = sentBy;
        this.subject = subject;
        this.body = body;
        this.recipientCount = recipientCount;
        this.truncated = truncated;
    }

    /**
     * A message about to be sent.
     *
     * <p>The bounds are checked here rather than only by the check constraints, so that a
     * creator who typed too much is told which field and how much — a constraint violation
     * surfacing as a 500 tells them nothing they can act on.
     *
     * @param segmentId the saved segment, or null for every backer. The name must be present
     *     with it and absent without it, which is {@code campaign_messages_segment_is_whole}
     * @param recipientCount how many people it reached, resolved before this is built and frozen
     *     here. Zero is allowed: a segment that matches nobody today is an ordinary answer
     * @param truncated whether the audience hit the platform's ceiling, so that a message which
     *     reached the first five thousand of six thousand says so
     */
    public static CampaignMessage sent(
            UUID id,
            UUID projectId,
            UUID segmentId,
            String segmentName,
            UUID sentBy,
            String subject,
            String body,
            int recipientCount,
            boolean truncated) {

        String trimmedSubject = subject == null ? "" : subject.strip();
        String trimmedBody = body == null ? "" : body.strip();

        if (trimmedSubject.isEmpty() || trimmedSubject.length() > MAX_SUBJECT) {
            throw new MessageContentInvalidException("subject", MAX_SUBJECT);
        }
        if (trimmedBody.isEmpty() || trimmedBody.length() > MAX_BODY) {
            throw new MessageContentInvalidException("body", MAX_BODY);
        }
        if ((segmentId == null) != (segmentName == null)) {
            throw new IllegalArgumentException("A segment is an identifier and a name, or it is neither");
        }
        if (recipientCount < 0) {
            throw new IllegalArgumentException("A message reaches nobody or somebody, never fewer than nobody");
        }

        return new CampaignMessage(
                id, projectId, segmentId, segmentName, sentBy, trimmedSubject, trimmedBody, recipientCount, truncated);
    }

    /** Whether this went to a saved segment rather than to every backer. */
    public boolean isTargeted() {
        return segmentId != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public String getSegmentName() {
        return segmentName;
    }

    public UUID getSentBy() {
        return sentBy;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CampaignMessage message && Objects.equals(id, message.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No subject and no body: this lands in logs, and the content of a message to backers
        // is not something an operator needs in order to trace one. The count is.
        return "CampaignMessage[id=" + id + ", project=" + projectId + ", recipients=" + recipientCount + "]";
    }
}
