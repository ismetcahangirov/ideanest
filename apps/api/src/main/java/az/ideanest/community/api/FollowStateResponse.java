package az.ideanest.community.api;

/**
 * Whether the caller follows this account, after the call they just made.
 *
 * <p>The mirror of {@link SaveStateResponse}, and its argument for reporting the state rather
 * than the outcome applies unchanged.
 *
 * @param following always true from the {@code POST} and false from the {@code DELETE}
 */
public record FollowStateResponse(boolean following) {
}
