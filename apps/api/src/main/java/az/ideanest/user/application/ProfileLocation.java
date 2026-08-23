package az.ideanest.user.application;

import java.util.UUID;

/**
 * One of V16's eighteen places, as a profile names it — §4.2's P-02.
 *
 * @param id the row {@code users.location_id} points at. Carried so that
 *     {@code ProfileEditing} can write it and never serialised, for
 *     {@link PublicProfile}'s reason about the account identifier: an identifier in a
 *     public body is a join key
 * @param slug the folded, URL-facing token — {@code baki}, {@code gence}. This is the half
 *     that is published, because it is the same value discovery's {@code ?city=} filter
 *     takes: a client renders the name and links to {@code /discover?city={slug}}, and the
 *     two land on the same eighteen rows because they are literally the same rows
 * @param name what the place is called. <strong>Always the {@code az} endonym</strong>, and
 *     that is a decision rather than a default. Resolving it in the reader's language would
 *     make {@code GET /v1/users/{slug}} answer differently per reader, which costs the
 *     shared {@code Cache-Control: public} revalidation that pays for the endpoint —
 *     {@code PublicProfileController} explains why that response deliberately carries
 *     nothing belonging to whoever is reading it. V16 also states the substantive half: the
 *     {@code az} row is the mandatory one and "the fallback for a proper noun is what the
 *     place calls itself". A locale-aware name is a change that has to reckon with the
 *     public endpoint's cache first
 */
public record ProfileLocation(UUID id, String slug, String name) {
}
