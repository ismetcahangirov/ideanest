package az.ideanest.community.api;

/**
 * Whether the caller has this campaign saved, after the call they just made.
 *
 * <p><strong>The state, not what happened.</strong> Saving is idempotent, so "created" and
 * "already saved" are the same success and a response that distinguished them would be asking
 * the client to care about a race it cannot observe — a retry after a dropped connection would
 * report differently from the attempt that was dropped, for the same button press.
 *
 * @param saved always true from the {@code POST} and false from the {@code DELETE}. A field
 *     rather than an empty body because a toggle renders from the answer, and because the shape
 *     has somewhere to grow — a save count, when a screen shows one
 */
public record SaveStateResponse(boolean saved) {
}
