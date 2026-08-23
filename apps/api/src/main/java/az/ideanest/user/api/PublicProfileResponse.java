package az.ideanest.user.api;

import az.ideanest.user.application.PublicProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A person's public profile, on the wire — §10.2's {@code GET /v1/users/{slug}}.
 *
 * <p><strong>Five fields, and the account identifier is not one of them.</strong>
 * {@link PublicProfile} carries it because the two archives on §4.2's page are keyed on
 * the account, and it stops here: an identifier in a public body is a join key, and a
 * client that can join on one is a client that can walk the account table. Every public
 * path to a person on this platform is a slug — the profile, the follow endpoints, the
 * campaign page's creator link — and this response is not the one that breaks that.
 *
 * <p><strong>Nulls are written out</strong>, unlike {@code ProjectPageResponse} beside it
 * in the project module, and the difference is what the two feed. That one renders a page and treats
 * absent and empty as the same thing; this feeds a profile with an editable about tab, and
 * a client that cannot tell "this person wrote no bio" from "the key I expected is
 * missing" will show a spinner in place of an empty state. The same reasoning
 * {@code PledgeResponse} gives.
 *
 * <p><strong>No counts.</strong> §4.2's page shows how many campaigns this account created
 * and backed, and neither number is here — {@code PublicProfiles} carries the argument in
 * full, and the short form is that answering them from the user module would mean the
 * module every other module depends on depending on two of them. A client that needs a
 * number reads the length of the list it is already rendering.
 *
 * @param slug the profile's own address, echoed so that a client holding only this record
 *     can still build a link back to it
 * @param joinedAt when the account was created. "Member since", which is the one claim on
 *     this page a stranger can check without taking the person's word for it
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicProfileResponse(String slug, String name, String avatarUrl, String bio, Instant joinedAt) {

    /** ASCII unit separator. Cannot occur in a slug, a name, a URL or an instant. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    public static PublicProfileResponse of(PublicProfile profile) {
        return new PublicProfileResponse(
                profile.slug(), profile.name(), profile.avatarUrl(), profile.bio(), profile.joinedAt());
    }

    /**
     * A validator for this exact body, per §10.3.
     *
     * <p>Field by field rather than over the serialised JSON, which is the split
     * {@code PublicProjectController} draws and lands on the other side of: that response
     * has seventeen fields, four of them nested and one a document of arbitrary depth, so
     * a canonical form would be a second serialiser to keep in step. This one is five
     * strings, and a canonical form written beside them makes forgetting to cover a new
     * field a one-line distance rather than a different file.
     *
     * <p>A digest and never {@code hashCode()}, for the reason {@code PublicReads} gives:
     * a tag has to mean the same thing on every instance of the service and after a
     * restart, and nothing guarantees a record's hash does.
     *
     * <p>The bio is hashed in full. It is the field most likely to be edited and the one
     * a reader is most likely to be looking at when it is, and a tag that covered only the
     * short fields would answer 304 to somebody revalidating a page that had changed.
     */
    public String etag() {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {slug, name, avatarUrl, bio, String.valueOf(joinedAt)}) {
            canonical.append(field).append(FIELD_SEPARATOR);
        }

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }
}
