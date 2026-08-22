package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A reminder sent to somebody who has not answered — §4.8's PM-24, §8.4's
 * {@code survey-nudge}.
 *
 * <p><strong>The row is the claim.</strong> It is written in the same transaction as
 * the outbox event that reminds somebody, exactly as {@code deadline_notices} is, so a
 * crash leaves them either unchased and unclaimed or chased and claimed.
 *
 * <p>It has to exist because the sweep's question — "who has not answered" — stays true
 * for as long as they have not, and every one of those days would otherwise be another
 * email. {@code attempt} is what bounds the total: one is a nudge and five is a
 * campaign of its own, and where that line falls is
 * {@code PledgeManagerProperties.Surveys.nudgeAttempts}.
 */
@Entity
@Table(name = "survey_nudges")
public class SurveyNudge {

    /** The survey, the pledge, and which reminder this was. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "survey_id", nullable = false, updatable = false)
        private UUID surveyId;

        @Column(name = "pledge_id", nullable = false, updatable = false)
        private UUID pledgeId;

        @Column(name = "attempt", nullable = false, updatable = false)
        private int attempt;

        protected Key() {
            // JPA.
        }

        public Key(UUID surveyId, UUID pledgeId, int attempt) {
            this.surveyId = Objects.requireNonNull(surveyId, "A reminder is about a survey");
            this.pledgeId = Objects.requireNonNull(pledgeId, "A reminder goes to a pledge's backer");
            this.attempt = attempt;
        }

        public UUID getSurveyId() {
            return surveyId;
        }

        public UUID getPledgeId() {
            return pledgeId;
        }

        public int getAttempt() {
            return attempt;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(surveyId, key.surveyId)
                    && Objects.equals(pledgeId, key.pledgeId)
                    && attempt == key.attempt;
        }

        @Override
        public int hashCode() {
            return Objects.hash(surveyId, pledgeId, attempt);
        }

        @Override
        public String toString() {
            return "Key[survey=" + surveyId + ", pledge=" + pledgeId + ", attempt=" + attempt + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected SurveyNudge() {
        // JPA.
    }

    public static SurveyNudge of(UUID surveyId, UUID pledgeId, int attempt, Instant at) {
        if (attempt < 1) {
            throw new IllegalArgumentException("A reminder is the first or a later one, not number " + attempt);
        }
        SurveyNudge nudge = new SurveyNudge();
        nudge.id = new Key(surveyId, pledgeId, attempt);
        nudge.sentAt = Objects.requireNonNull(at, "A reminder was sent at a moment");
        return nudge;
    }

    public UUID getSurveyId() {
        return id.getSurveyId();
    }

    public UUID getPledgeId() {
        return id.getPledgeId();
    }

    public int getAttempt() {
        return id.getAttempt();
    }

    public Instant getSentAt() {
        return sentAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SurveyNudge nudge && Objects.equals(id, nudge.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SurveyNudge[" + id + "]";
    }
}
