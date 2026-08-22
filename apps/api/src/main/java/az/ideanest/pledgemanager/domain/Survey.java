package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A set of questions a creator asks their backers after funding closes — §4.8's
 * PM-01.
 *
 * <h2>{@code sentAt} is the whole of "draft" and "sent"</h2>
 *
 * <p>V22 made the same decision for {@code project_updates.published_at} and this
 * makes it again: a state column beside a timestamp is two facts that can disagree,
 * and the one a support script updates is never the one the reads filter on.
 *
 * <p>A draft is editable and invisible. A sent survey is neither: its questions
 * freeze, because a question edited after four hundred people answered it changes what
 * they were asked without changing what they said. What stays editable is the
 * covering note and the cut-off — the first because it is prose nobody answered, and
 * the second because extending a deadline is the thing creators most often need to do
 * and refusing it would leave them re-sending the whole survey.
 *
 * <h2>The cut-off is compared, never swept</h2>
 *
 * <p>PM-06 lets a backer edit until a stated cut-off. {@code respondBy} is that
 * instant and nothing closes it: every write compares it to the clock. A job that
 * closed surveys is a job that can be late, and late here means accepting an answer
 * after the creator placed the order.
 *
 * <p>Null means no cut-off has been set, which is the honest default and is not the
 * same as closed.
 */
@Entity
@Table(name = "surveys")
public class Survey {

    private static final int MAX_TITLE = 150;

    private static final int MAX_MESSAGE = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message")
    private String message;

    @Column(name = "respond_by")
    private Instant respondBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_to")
    private Integer sentTo;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Survey() {
        // JPA.
    }

    /** A new, unsent survey. */
    public static Survey draft(UUID id, UUID projectId, UUID createdBy, String title, String message,
            Instant respondBy) {

        Survey survey = new Survey();
        survey.id = Objects.requireNonNull(id, "A survey has an identifier");
        survey.projectId = Objects.requireNonNull(projectId, "A survey belongs to a campaign");
        survey.createdBy = Objects.requireNonNull(createdBy, "A survey was built by somebody");
        survey.describe(title, message, respondBy);
        return survey;
    }

    /**
     * The title, the covering note and the cut-off.
     *
     * <p>Permitted after the send, unlike the questions — see the class comment.
     *
     * @throws SurveyContentInvalidException when the title is blank or either text is
     *     too long
     */
    public void describe(String title, String message, Instant respondBy) {
        this.title = required(title, "title", MAX_TITLE);
        this.message = optional(message, "message", MAX_MESSAGE);

        if (sentAt != null && respondBy != null && !respondBy.isAfter(sentAt)) {
            // V35 refuses the same row. A cut-off before the send is a survey that was
            // closed on arrival: every backer received an invitation to a form that
            // refuses them, and the creator has no way to tell from the screen.
            throw new SurveyContentInvalidException(
                    "respondBy", "The deadline has to be after the survey was sent.");
        }
        this.respondBy = respondBy;
    }

    /**
     * Marks the survey as sent, to this many backers.
     *
     * <p>One-way. Re-sending is a new survey, because "sent" is a fact about a message
     * several thousand people already received and un-sending it is not available.
     */
    public void sent(Instant at, int recipients) {
        if (sentAt != null) {
            throw new IllegalStateException("Survey " + id + " has already been sent");
        }
        if (recipients < 0) {
            throw new IllegalArgumentException("A send reaches at least nobody, not " + recipients);
        }
        if (respondBy != null && !respondBy.isAfter(at)) {
            throw new SurveyContentInvalidException(
                    "respondBy", "The deadline has already passed. Move it before sending.");
        }
        this.sentAt = Objects.requireNonNull(at, "A send happened at a moment");
        this.sentTo = recipients;
    }

    public boolean isSent() {
        return sentAt != null;
    }

    /** Whether a backer may still answer or change an answer, as of this moment (PM-06). */
    public boolean isOpen(Instant now) {
        return isSent() && (respondBy == null || respondBy.isAfter(now));
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public Instant getRespondBy() {
        return respondBy;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Integer getSentTo() {
        return sentTo;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String required(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new SurveyContentInvalidException(field, "This is required.");
        }
        if (trimmed.length() > max) {
            throw new SurveyContentInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }

    /** Absent and blank are the same survey, and null is the one representation of it. */
    private static String optional(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new SurveyContentInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Survey survey && Objects.equals(id, survey.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Survey[" + id + ", sent=" + isSent() + "]";
    }
}
