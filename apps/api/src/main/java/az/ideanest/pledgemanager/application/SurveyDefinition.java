package az.ideanest.pledgemanager.application;

import java.time.Instant;
import java.util.List;

/**
 * A survey as its creator is asking for it — §4.8's PM-01.
 *
 * <p>The whole thing, questions included. A survey is replaced wholesale rather than
 * patched, for {@code RewardService}'s reason about rate tables: a question left out
 * of the body is one the creator deleted, and merging would leave a question on the
 * form that they believe they removed.
 *
 * @param respondBy PM-06's cut-off, or null for none. Null is not "closed" — see
 *     {@code Survey}
 * @param questions in the order they will be asked. The order is the list's; there is
 *     no position field to disagree with it
 */
public record SurveyDefinition(String title, String message, Instant respondBy, List<QuestionDefinition> questions) {
}
