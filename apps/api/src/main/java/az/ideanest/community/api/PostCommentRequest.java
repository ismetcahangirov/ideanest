package az.ideanest.community.api;

/**
 * {@code POST /v1/projects/{id}/comments} and {@code POST /v1/comments/{id}/reply}.
 *
 * <p><strong>The body carries no bean-validation annotation</strong>, for
 * {@code PublishUpdateRequest}'s reason: the rule lives in {@code CommentBody}, which
 * the entity calls on the way in, so there is exactly one statement of "what a comment
 * may say" and a second write path inherits it rather than restating it. Annotating it
 * here as well would mean two messages for one rule and a race to see which fired.
 *
 * <p><strong>One request type for both routes, and nothing else in it.</strong> What is
 * being replied to is the path; whose comment it is is the access token. A
 * {@code parentId} or an {@code authorId} in a body would be a field a client could
 * choose, and both are the fields that decide where a comment lands and whose name is
 * on it.
 *
 * <p>There is deliberately no {@code byCreator} either — see {@code CommentService} for
 * why a claim of authority is never accepted from the side making the claim.
 */
public record PostCommentRequest(String body) {
}
