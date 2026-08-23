package az.ideanest.user.api;

import az.ideanest.user.application.ProfileLocation;

/**
 * Where somebody is, on the wire — §4.2's P-02 (#276).
 *
 * <p><strong>Read-only, and only half of {@link ProfileLocation}.</strong> The row's
 * identifier stops here, for the reason {@code PublicProfileResponse} gives about the account
 * identifier: an identifier in a public body is a join key. What crosses is the pair a client
 * actually uses — the name to render, and the slug to link with.
 *
 * <p><strong>A patch sends {@code locationSlug} instead, not this record.</strong> The write
 * takes the one value the client chose from a list; sending the name back would let a request
 * carry a name that contradicts its slug, and the endpoint would then have to decide which of
 * the two it believes.
 *
 * @param slug the folded, URL-facing token — {@code baki}, {@code gence}. The same value
 *     discovery's {@code ?city=} filter takes, which is what lets a profile link into
 *     {@code /discover?city={slug}} and land on the campaigns that are actually there
 * @param name what the place is called. Always the {@code az} endonym; {@link ProfileLocation}
 *     argues why it is not the reader's language, and the short form is that a per-reader name
 *     would cost {@code GET /v1/users/{slug}} its shared cache
 */
public record LocationBody(String slug, String name) {

    /** Null in, null out: an account that has not said where it is, which is most of them. */
    public static LocationBody of(ProfileLocation location) {
        return location == null ? null : new LocationBody(location.slug(), location.name());
    }
}
