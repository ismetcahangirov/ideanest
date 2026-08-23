package az.ideanest.user.api;

import az.ideanest.user.application.ProfileSocialLink;
import az.ideanest.user.domain.SocialPlatform;

/**
 * One of a person's accounts elsewhere, in a request and in a response — §4.2's P-03 (#276).
 *
 * <p>One record for both directions, as {@code CoverImageBody} is and for its reason: it is
 * the same two values, and two records would drift the first time a field was added to one
 * of them.
 *
 * <p><strong>No identifier and no position.</strong> An identifier would invite a client to
 * address one link, which is a second write path nobody asked for; the position is the index
 * in the array, in both directions, and sending it as well would be two statements of one
 * order that can disagree. {@code ProfileEdit.socialLinks} takes the whole list or nothing.
 *
 * @param platform one of nine. Bound directly to the enum, so an unrecognised name is a 400
 *     from the binding before any handler runs — the route {@code ProfileVisibilityRequest}
 *     takes, and the reason {@link SocialPlatform} is closed
 * @param url where the account is. <strong>User-supplied, https-only, and never fetched by
 *     this server</strong> — {@code OwnProfileResponse} states what that means for whoever
 *     renders it
 */
public record SocialLinkBody(SocialPlatform platform, String url) {

    public static SocialLinkBody of(ProfileSocialLink link) {
        return new SocialLinkBody(link.platform(), link.url());
    }

    public ProfileSocialLink toDomain() {
        return new ProfileSocialLink(platform, url);
    }
}
