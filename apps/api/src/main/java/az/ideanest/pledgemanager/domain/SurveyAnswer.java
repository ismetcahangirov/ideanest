package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One answer to one question.
 *
 * <h2>Always an array</h2>
 *
 * <p>Whatever the type. {@code TEXT}, {@code CHOICE} and {@code DATE} hold exactly one
 * element and {@code MULTI_CHOICE} holds one or more, which gives every reader one
 * shape — instead of a {@code value} column and a {@code values} column with a check
 * saying exactly one of them is set, and a branch at every call site to find out which.
 *
 * <p>The per-type cardinality is <strong>not</strong> a database constraint, and V35
 * says so out loud: a check cannot join to {@code survey_questions} to find out which
 * type it is answering. {@code SurveyResponseService} refuses a two-element answer to a
 * {@code CHOICE} with a sentence; what the table guarantees is that an answer is
 * non-empty and that none of its elements is blank.
 *
 * <h2>The words, not a reference</h2>
 *
 * <p>A chosen option is stored as the text the backer saw. An index into the
 * question's option array would break the moment a creator reordered them — silently,
 * turning every "Medium" into a "Large" — and a foreign key would need a table
 * {@code SurveyQuestion} deliberately does not have. What a backer chose is the words
 * they were shown.
 */
@Entity
@Table(name = "survey_answers")
public class SurveyAnswer {

    /** The response and the question. See {@code ShippingRule.Key} for why it is a class. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "response_id", nullable = false, updatable = false)
        private UUID responseId;

        @Column(name = "question_id", nullable = false, updatable = false)
        private UUID questionId;

        protected Key() {
            // JPA.
        }

        public Key(UUID responseId, UUID questionId) {
            this.responseId = Objects.requireNonNull(responseId, "An answer belongs to a response");
            this.questionId = Objects.requireNonNull(questionId, "An answer answers a question");
        }

        public UUID getResponseId() {
            return responseId;
        }

        public UUID getQuestionId() {
            return questionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(responseId, key.responseId)
                    && Objects.equals(questionId, key.questionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(responseId, questionId);
        }

        @Override
        public String toString() {
            return "Key[response=" + responseId + ", question=" + questionId + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "survey_id", nullable = false, updatable = false)
    private UUID surveyId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "value", nullable = false)
    private String[] value;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SurveyAnswer() {
        // JPA.
    }

    public static SurveyAnswer of(UUID responseId, UUID questionId, UUID surveyId, List<String> value) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.id = new Key(responseId, questionId);
        answer.surveyId = Objects.requireNonNull(surveyId, "An answer belongs to a survey");
        answer.replaceWith(value);
        return answer;
    }

    /** PM-06: the backer changed this answer. */
    public void replaceWith(List<String> value) {
        Objects.requireNonNull(value, "An answer has a value");
        if (value.isEmpty()) {
            // An empty answer is the absence of one, which is a deleted row rather
            // than a row holding nothing — otherwise "did they answer" has two
            // representations and the export would show a blank cell for both.
            throw new IllegalArgumentException("An answer with no value is not an answer");
        }
        this.value = value.toArray(String[]::new);
    }

    public UUID getResponseId() {
        return id.getResponseId();
    }

    public UUID getQuestionId() {
        return id.getQuestionId();
    }

    public UUID getSurveyId() {
        return surveyId;
    }

    public List<String> getValue() {
        return List.of(value);
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
        return other instanceof SurveyAnswer answer && Objects.equals(id, answer.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // The identifiers only. An answer can be a name, a size, or anything else a
        // creator thought to ask, and a log line is not where it belongs.
        return "SurveyAnswer[" + id + "]";
    }
}
