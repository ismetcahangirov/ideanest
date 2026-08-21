package az.ideanest.community.domain;

/**
 * A message subject or body that is empty or too long.
 *
 * <p>A 422 naming the field and the bound, in the voice of {@code UpdateContentInvalidException}
 * beside it. The bound travels in the response so a client can show a counter rather than teach
 * the rule by refusing a submission the reader has already spent five minutes on.
 *
 * <p>The bounds themselves are {@code CampaignMessage}'s constants and
 * {@code campaign_messages_subject_length} / {@code campaign_messages_body_length} in the
 * schema. Two statements of one rule, deliberately: the check constraint is what refuses a
 * support script, and this is what refuses a person in a sentence.
 */
public class MessageContentInvalidException extends RuntimeException {

    private final String field;
    private final int maxLength;

    public MessageContentInvalidException(String field, int maxLength) {
        super("A message " + field + " is between 1 and " + maxLength + " characters");
        this.field = field;
        this.maxLength = maxLength;
    }

    /** Which of the two: {@code subject} or {@code body}. */
    public String field() {
        return field;
    }

    public int maxLength() {
        return maxLength;
    }
}
