package az.ideanest.community.api;

import az.ideanest.community.application.NewFaq;

/**
 * {@code POST /v1/projects/{id}/faqs}.
 *
 * <p><strong>Neither field carries a bean-validation annotation, and that is
 * deliberate.</strong> Their rules live in {@code FaqContent}, which the entity calls on
 * the way in, so there is exactly one statement of "what an FAQ entry may say" and it is
 * the one a second write path would inherit. Annotating them here as well would mean two
 * messages for one rule and a race to see which fired first. {@code PublishUpdateRequest}
 * makes the same choice for the same reason.
 *
 * <p>Nothing here is {@code @NotNull} either, because unlike an update's
 * {@code visibility} neither field is an enum that would reach a constructor and throw:
 * a null question is refused by {@code FaqContent} with the message that names the field,
 * which is the answer the creator can act on.
 *
 * <p><strong>No position.</strong> A new entry goes at the end of the creator's list —
 * see {@code ProjectFaqService#add}. Letting a create body name a position would be a
 * second way to reorder, and one that does not rewrite the rows it displaces.
 */
public record CreateFaqRequest(String question, String answer) {

    /** The command the service takes, so that no field the client may not choose can arrive from here. */
    public NewFaq toCommand() {
        return new NewFaq(question, answer);
    }
}
