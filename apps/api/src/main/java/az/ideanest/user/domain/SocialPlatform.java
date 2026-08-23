package az.ideanest.user.domain;

/**
 * Where else a person publishes — §4.2's P-03 (#276).
 *
 * <p><strong>A closed set, and closed is the point.</strong> A free {@code platform}
 * string would leave every client rendering an icon for a name it has never seen, and the
 * only honest fallback for that is a generic link — at which point the field is decoration
 * and the platform vocabulary is whatever people typed. Nine values means a web client, an
 * iOS client and an Android client can each hold nine icons and be complete.
 *
 * <p>Mirrored by {@code user_social_links_platform_is_known}, which is what makes it a rule
 * rather than a convention: a support query or a second write path added in two years
 * cannot put a tenth value in the column. Adding one is a one-line migration and a
 * deliberate act, which is the right weight for a decision that adds an icon to three
 * clients.
 *
 * <p>Bound directly from the request body, so an unrecognised name is refused by the
 * binding with a 400 before any handler runs — the same route
 * {@link ProfileVisibility} takes and for the same reason.
 */
public enum SocialPlatform {
    INSTAGRAM,
    FACEBOOK,
    /** Formerly Twitter. Stored under its current name; a rename is not a second platform. */
    X,
    YOUTUBE,
    TIKTOK,
    LINKEDIN,
    TELEGRAM,
    GITHUB,
    BEHANCE
}
