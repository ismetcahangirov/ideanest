package az.ideanest.user.application;

import az.ideanest.shared.Patched;
import java.util.List;

/**
 * A partial edit of a profile, with JSON Merge-Patch semantics (RFC 7396) — §4.2's P-01 to
 * P-03 (#276).
 *
 * <p>Every field is a {@link Patched}: one the client did not mention is left alone, and one
 * explicitly set to {@code null} is cleared. {@code ProjectPatch} carries the same shape for
 * the campaign editor and {@link Patched} carries the argument, which applies here with one
 * change of noun — a profile form that autosaved a name and read the absent keys as "clear
 * these" would delete somebody's biography, their site and their links in a request that
 * looks entirely ordinary in a log.
 *
 * <p><strong>No {@code slug}.</strong> Not absent by oversight: {@link OwnProfile#slug()}
 * states why the public address of a profile is not something its owner may quietly change.
 *
 * @param socialLinks <strong>the whole list, or nothing.</strong> There is no way to add or
 *     remove one link, and that is the decision {@code survey_questions} took about ordered
 *     child rows: the list is short, a client always holds all of it, and "insert between
 *     the second and the third" is a rewrite either way. An explicit {@code null} or an
 *     empty array removes every link
 */
public record ProfileEdit(
        Patched<String> name,
        Patched<String> bio,
        Patched<String> avatarUrl,
        Patched<String> websiteUrl,
        Patched<String> locationSlug,
        Patched<List<ProfileSocialLink>> socialLinks) {

    public ProfileEdit {
        // Absence is the neutral value, so a null component becomes absent rather than
        // "clear this field". See Patched: Jackson's absent hook already does this, and
        // this is the belt to its braces.
        name = Patched.orAbsent(name);
        bio = Patched.orAbsent(bio);
        avatarUrl = Patched.orAbsent(avatarUrl);
        websiteUrl = Patched.orAbsent(websiteUrl);
        locationSlug = Patched.orAbsent(locationSlug);
        socialLinks = Patched.orAbsent(socialLinks);
    }
}
