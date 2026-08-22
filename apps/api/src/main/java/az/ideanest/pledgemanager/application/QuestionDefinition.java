package az.ideanest.pledgemanager.application;

import java.util.List;
import java.util.UUID;

/**
 * One question as its creator is asking for it — §4.8's PM-01 to PM-03.
 *
 * <p>No identifier and no position. A question has no natural key, so a rewritten
 * survey creates new rows — {@code SurveyService.replaceQuestions} says why that is
 * safe and why a diff would not be — and the order is the list's.
 *
 * @param type the wire name of a {@code QuestionType}: {@code TEXT}, {@code CHOICE},
 *     {@code MULTI_CHOICE}, {@code DATE} or {@code ADDRESS}
 * @param choices the options, for the two types that have them. Empty or absent for
 *     the others, and giving options to a type that has none is refused rather than
 *     ignored — a creator who did that picked the wrong type and would not find out
 *     until the answers came back as free prose
 * @param rewardTierId PM-02: null asks every backer, a tier asks only those who chose
 *     it
 */
public record QuestionDefinition(
        String prompt, String helpText, String type, boolean required, List<String> choices, UUID rewardTierId) {
}
