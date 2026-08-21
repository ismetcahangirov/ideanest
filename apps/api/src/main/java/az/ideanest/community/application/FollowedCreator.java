package az.ideanest.community.application;

import az.ideanest.community.domain.Follow;
import az.ideanest.user.application.UserAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /v1/me/following}: an account somebody follows, and when they started.
 *
 * <p><strong>Three fields, and no address.</strong> §17.4 keeps addresses out of anything that
 * is not the account's own view of itself, and a list of people somebody follows is not that —
 * so what is published here is the name and the slug, which are already on a public profile.
 *
 * @param creatorId the account being followed
 * @param name their display name, as it is now
 * @param slug their half of every public path, and how the client links to them
 * @param followedAt when this account started following, which orders the list
 */
public record FollowedCreator(UUID creatorId, String name, String slug, Instant followedAt) {

    static FollowedCreator of(Follow row, UserAccount account) {
        return new FollowedCreator(account.id(), account.name(), account.slug(), row.getCreatedAt());
    }
}
