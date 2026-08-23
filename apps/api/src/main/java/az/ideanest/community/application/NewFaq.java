package az.ideanest.community.application;

/**
 * What a creator asked to add to the FAQ tab.
 *
 * <p>A command rather than the request body, so that the service is testable without an
 * HTTP layer and so that a field the client may not choose — the campaign, the position
 * — has nowhere to arrive from. The position is allocated by {@link ProjectFaqService}
 * as one past the last entry, because a new question goes at the end of the list until
 * the creator drags it somewhere else.
 *
 * @param question the creator's phrasing of what people keep asking. Its rules live in
 *     {@code FaqContent} and are applied by the entity, not here
 * @param answer the answer, as prose. See V47 for why it is not a document
 */
public record NewFaq(String question, String answer) {
}
