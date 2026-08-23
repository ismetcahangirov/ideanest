package az.ideanest.user.api;

import az.ideanest.user.application.PublicProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * A person's public profile, on the wire — §10.2's {@code GET /v1/users/{slug}}.
 *
 * <p><strong>Eight fields, and the account identifier is not one of them.</strong>
 * {@link PublicProfile} carries it because the two archives on §4.2's page are keyed on the
 * account, and it stops here: an identifier in a public body is a join key, and a client that
 * can join on one is a client that can walk the account table. Every public path to a person
 * on this platform is a slug — the profile, the follow endpoints, the campaign page's creator
 * link — and this response is not the one that breaks that.
 *
 * <p><strong>Nulls are written out</strong>, unlike {@code ProjectPageResponse} beside it in
 * the project module, and the difference is what the two feed. That one renders a page and
 * treats absent and empty as the same thing; this feeds a profile with an editable about tab,
 * and a client that cannot tell "this person wrote no bio" from "the key I expected is
 * missing" will show a spinner in place of an empty state. The same reasoning
 * {@code PledgeResponse} gives. {@link #socialLinks} follows it in the other direction: an
 * account with no links carries an empty array rather than a null, because an array is a
 * thing a client maps over.
 *
 * <p><strong>No counts.</strong> §4.2's page shows how many campaigns this account created and
 * backed, and neither number is here — {@code PublicProfiles} carries the argument in full,
 * and the short form is that answering them from the user module would mean the module every
 * other module depends on depending on two of them. A client that needs a number reads the
 * length of the list it is already rendering.
 *
 * <h2>These URLs are user-supplied, and rendering them safely is the client's job</h2>
 *
 * <p><strong>{@link #websiteUrl} and every {@link #socialLinks} address are strings a stranger
 * typed into a form.</strong> The server refuses anything that is not {@code https://} — see
 * {@code ProfileEditing}, and {@code users_website_url_is_https} one layer below it — which
 * closes the {@code javascript:} and {@code data:} scheme entirely. What it cannot close is
 * everything an <em>ordinary</em> https link does when a page publishes it.
 *
 * <p>So whoever renders these anchors owes them three attributes, and the reason for each is
 * different:
 *
 * <ul>
 *   <li>{@code nofollow ugc} — this is user-generated content on an indexable page, which is
 *       the cheapest backlink a spammer can get anywhere on this platform. Without it the
 *       profile editor is an SEO product we are giving away.</li>
 *   <li>{@code noopener} — a link opened in a new tab hands the destination a
 *       {@code window.opener} reference to the page it came from, which is enough to navigate
 *       it somewhere else. A convincing sign-in page is one line of JavaScript away.</li>
 *   <li>{@code noreferrer} — the profile being read is nobody's business but the reader's, and
 *       a {@code Referer} header tells every site a person clicks through to exactly which
 *       profile they were looking at.</li>
 * </ul>
 *
 * <p>Stated in the contract rather than left to the client because it is the contract's
 * problem: the server publishes an address it did not write and cannot vouch for, and a
 * response that said nothing about that would be handing three clients the same hazard to
 * rediscover separately. {@code rel="nofollow ugc noopener noreferrer"}.
 *
 * @param slug the profile's own address, echoed so that a client holding only this record can
 *     still build a link back to it
 * @param joinedAt when the account was created. "Member since", which is the one claim on this
 *     page a stranger can check without taking the person's word for it
 * @param location null for an account that has not said where it is. The {@code slug} on it is
 *     a link into {@code /discover?city=}, which is the reason it is a shared vocabulary
 * @param socialLinks in the order their owner put them, and empty rather than null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicProfileResponse(
        String slug,
        String name,
        String avatarUrl,
        String bio,
        Instant joinedAt,
        String websiteUrl,
        LocationBody location,
        List<SocialLinkBody> socialLinks) {

    /** ASCII unit separator. Cannot occur in a slug, a name, a URL or an instant. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    /**
     * ASCII record separator, for the boundary between two links.
     *
     * <p>A second character rather than reusing the one above, so that a list cannot collide
     * with the scalar fields around it: with one separator, two links would flatten into four
     * fields and a profile whose links were regrouped could hash to the same value.
     */
    private static final char RECORD_SEPARATOR = (char) 0x1e;

    public static PublicProfileResponse of(PublicProfile profile) {
        return new PublicProfileResponse(
                profile.slug(),
                profile.name(),
                profile.avatarUrl(),
                profile.bio(),
                profile.joinedAt(),
                profile.websiteUrl(),
                LocationBody.of(profile.location()),
                profile.socialLinks().stream().map(SocialLinkBody::of).toList());
    }

    /**
     * A validator for this exact body, per §10.3.
     *
     * <p>Field by field rather than over the serialised JSON, which is the split
     * {@code PublicProjectController} draws and lands on the other side of: that response has
     * seventeen fields, four of them nested and one a document of arbitrary depth, so a
     * canonical form would be a second serialiser to keep in step. This one is eight values,
     * one of them a short list of pairs, and a canonical form written beside them makes
     * forgetting to cover a new field a one-line distance rather than a different file.
     *
     * <p>A digest and never {@code hashCode()}, for the reason {@code PublicReads} gives: a tag
     * has to mean the same thing on every instance of the service and after a restart, and
     * nothing guarantees a record's hash does.
     *
     * <p><strong>Every field is covered, and #276's three are the reason to say so again.</strong>
     * The bio is hashed in full because it is the field most likely to be edited and the one a
     * reader is most likely to be looking at when it is. The site, the location and the links
     * are hashed for the identical reason and are the ones a canonical form written before them
     * would have missed: a tag that covered only the original five would answer 304 to somebody
     * revalidating a page whose links had changed, and the profile would keep showing an
     * address its owner had deleted. The links are hashed <em>in order</em>, because reordering
     * them is a change to the page.
     */
    public String etag() {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {
            slug,
            name,
            avatarUrl,
            bio,
            String.valueOf(joinedAt),
            websiteUrl,
            location == null ? null : location.slug(),
            location == null ? null : location.name()
        }) {
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        for (SocialLinkBody link : socialLinks) {
            canonical.append(link.platform())
                    .append(FIELD_SEPARATOR)
                    .append(link.url())
                    .append(RECORD_SEPARATOR);
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
