package az.ideanest.community.api;

import az.ideanest.community.application.FollowedCreator;
import az.ideanest.community.application.FollowedCreatorPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /v1/me/following}: one page of the accounts the caller follows.
 *
 * <p>The mirror of {@link SavedListResponse}, including its rule about {@code nextCursor}.
 *
 * @param items the accounts, newest follow first
 * @param nextCursor the opaque value to pass back, or null at the end of the list
 */
public record FollowingListResponse(List<Item> items, String nextCursor) {

    public static FollowingListResponse of(FollowedCreatorPage page) {
        return new FollowingListResponse(
                page.items().stream().map(Item::of).toList(),
                page.next() == null ? null : page.next().encode());
    }

    /**
     * One followed account.
     *
     * <p><strong>No address.</strong> §17.4 keeps addresses to the account's own view of
     * itself, and this is a list of other people; the name and the slug are already on a
     * public profile, and the identifier is here so a client can match a row against a follow
     * button it has already rendered.
     */
    public record Item(UUID creatorId, String name, String slug, Instant followedAt) {

        static Item of(FollowedCreator creator) {
            return new Item(creator.creatorId(), creator.name(), creator.slug(), creator.followedAt());
        }
    }
}
