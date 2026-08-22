package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * An answer the platform will not store — §4.8's PM-05.
 *
 * <p>Carries the question, because a survey is a form with up to thirty controls on it
 * and a refusal that does not say which one is answered by re-reading all of them.
 *
 * <p>Every case is a refusal rather than a correction, and that is the rule this class
 * exists to keep. A silently dropped answer is one the backer believes they gave, and
 * the creator manufactures from what they did not: an option that no longer exists, a
 * second value on a single-choice question, an answer to a question this pledge was
 * never asked. All of them are a client that is out of date, and all of them are worth
 * a round trip.
 *
 * <p>422: the body is well-formed and the caller is entitled to answer. It is the
 * relationship between what they sent and what they were asked that is wrong.
 */
public class AnswerInvalidException extends RuntimeException {

    private final UUID questionId;

    public AnswerInvalidException(UUID questionId, String message) {
        super(message);
        this.questionId = questionId;
    }

    /** Which question, so the form can highlight it. */
    public UUID questionId() {
        return questionId;
    }
}
