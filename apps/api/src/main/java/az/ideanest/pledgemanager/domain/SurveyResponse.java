package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One backer's answers to one survey — §4.8's PM-05.
 *
 * <p><strong>Keyed by pledge, not by account.</strong> What a backer is asked depends
 * on the tier they chose (PM-02) and what the creator ships is per pledge, so the
 * pledge is the identity that matters. {@code pledges} already carries the account;
 * {@code backerId} is denormalised here so that "my surveys" is an index rather than a
 * join on every read.
 *
 * <p><strong>PM-06 is an edit of this row, never a second one.</strong> Two rows would
 * make "what did they answer" a question with an ordering in it, and the ordering would
 * decide what gets manufactured. {@code submittedAt} moves on every edit and is what a
 * creator reads to find out whether an answer changed after they placed an order.
 */
@Entity
@Table(name = "survey_responses")
public class SurveyResponse {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "survey_id", nullable = false, updatable = false)
    private UUID surveyId;

    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "backer_id", nullable = false, updatable = false)
    private UUID backerId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SurveyResponse() {
        // JPA.
    }

    public static SurveyResponse of(UUID id, UUID surveyId, UUID pledgeId, UUID backerId, Instant at) {
        SurveyResponse response = new SurveyResponse();
        response.id = Objects.requireNonNull(id, "A response has an identifier");
        response.surveyId = Objects.requireNonNull(surveyId, "A response answers a survey");
        response.pledgeId = Objects.requireNonNull(pledgeId, "A response belongs to a pledge");
        response.backerId = Objects.requireNonNull(backerId, "A response was given by somebody");
        response.submittedAt = Objects.requireNonNull(at, "A response was given at a moment");
        return response;
    }

    /** PM-06: the backer changed their answers. */
    public void resubmitted(Instant at) {
        this.submittedAt = Objects.requireNonNull(at, "A resubmission happened at a moment");
    }

    public UUID getId() {
        return id;
    }

    public UUID getSurveyId() {
        return surveyId;
    }

    public UUID getPledgeId() {
        return pledgeId;
    }

    public UUID getBackerId() {
        return backerId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SurveyResponse response && Objects.equals(id, response.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SurveyResponse[" + id + ", pledge=" + pledgeId + "]";
    }
}
