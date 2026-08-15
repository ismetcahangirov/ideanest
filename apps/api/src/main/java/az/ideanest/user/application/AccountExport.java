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

    /** The first version of the format. Bumped when a reader would have to change. */
    public static final String FORMAT = "ideanest.account-export.v1";

    /**
     * @param deletionRequestedAt present only while a deletion is pending. It is
     *     the user's own instruction and they are entitled to see it in their
     *     own data
     */
    public record Account(
            UUID id,
            String email,
            String name,
            String slug,
            String bio,
            String avatarUrl,
            String locale,
            String currency,
            Instant emailVerifiedAt,
            Instant createdAt,
            Instant updatedAt,
            Instant deletionRequestedAt,
            Instant deletionScheduledAt) {
    }
}
