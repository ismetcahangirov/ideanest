package az.ideanest.user.application;

import az.ideanest.user.domain.SocialPlatform;

/**
 * One of a person's accounts elsewhere, in the shape the application layer works in —
 * §4.2's P-03.
 *
 * <p>Two fields, because a link is a platform and an address. The row's identifier, its
 * position and its timestamps stop at the repository: the position is an artefact of how
 * the list is stored rather than something a client sends or reads — order is the order of
 * the array, in both directions — and an identifier would invite a client to address one
 * link, which is a second write path nobody asked for. {@code ProfileEditing} rewrites the
 * whole list, so the array <em>is</em> the list.
 *
 * @param platform which service. Closed, and {@link SocialPlatform} argues why
 * @param url where the account is. User-supplied, never fetched by this server, and
 *     https-only — the rule is enforced in {@code ProfileEditing} and again by
 *     {@code user_social_links_url_is_https}
 */
public record ProfileSocialLink(SocialPlatform platform, String url) {
}
