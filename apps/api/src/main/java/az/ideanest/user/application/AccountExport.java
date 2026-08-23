package az.ideanest.user.application;

import az.ideanest.user.application.AccountSecurity.SessionRecord;
import az.ideanest.user.application.AccountSecurity.VerificationRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything held about one person, in one document.
 *
 * <p>Serialised straight to JSON, so this record <em>is</em> the published
 * shape: a field added here appears in somebody's export the next day, and one
 * removed breaks whatever they wrote to read it. Hence {@code format}, which a
 * reader can branch on rather than guessing from the keys present.
 *
 * @param format the shape of this document, versioned independently of the API
 * @param exportedAt when it was produced. An export is a snapshot, and a file
 *     found on a disk two years later should say what it is a snapshot of
 * @param account the profile
 * @param sessions every sign-in, live or ended
 * @param verifications every verification and reset link issued, and what
 *     became of it
 */
public record AccountExport(
        String format,
        Instant exportedAt,
        Account account,
        List<SessionRecord> sessions,
        List<VerificationRecord> verifications) {

    /**
     * The first version of the format. Bumped when a reader would have to change.
     *
     * <p><strong>Not bumped by #276</strong>, and the rule above is why rather than
     * convenience: that change added keys to {@link Account} and removed none, so a reader
     * written against v1 still parses a v1 document and still finds every key it looked for.
     * Bumping would break the branch of every reader that has one, in order to announce three
     * keys they were not asking about.
     */
    public static final String FORMAT = "ideanest.account-export.v1";

    /**
     * @param deletionRequestedAt present only while a deletion is pending. It is
     *     the user's own instruction and they are entitled to see it in their
     *     own data
     * @param websiteUrl §4.2's P-02 (#276), or null
     * @param location the slug of one of V16's eighteen places, or null. The slug rather than
     *     the identifier, because a uuid means nothing in a file somebody opens two years
     *     later, and rather than the localised name, because the slug is the value that will
     *     still resolve if the name is ever retranslated
     * @param socialLinks §4.2's P-03 (#276), in the order their owner put them. Empty rather
     *     than null. <strong>Added with the fields themselves rather than after them</strong>:
     *     this class's own comment says an export that silently omits a category is worse than
     *     one that has not got there, and a profile editor that shipped without its data
     *     reaching the export would be exactly that omission
     */
    public record Account(
            UUID id,
            String email,
            String name,
            String slug,
            String bio,
            String avatarUrl,
            String websiteUrl,
            String location,
            List<SocialLink> socialLinks,
            String locale,
            String currency,
            Instant emailVerifiedAt,
            Instant createdAt,
            Instant updatedAt,
            Instant deletionRequestedAt,
            Instant deletionScheduledAt) {

        public Account {
            socialLinks = socialLinks == null ? List.of() : List.copyOf(socialLinks);
        }
    }

    /**
     * One of the account's own links, in the export.
     *
     * <p>Its own record rather than the application's {@code ProfileSocialLink}, for the reason
     * this file's comment gives about itself: this record <em>is</em> a published shape, and
     * reusing an internal one would make every change to it a change to somebody's export
     * format. The position is omitted — the array is the order — and so is the row identifier,
     * which is a fact about our storage rather than about the person.
     *
     * @param platform one of {@code SocialPlatform}'s nine, as a string
     */
    public record SocialLink(String platform, String url) {
    }
}
