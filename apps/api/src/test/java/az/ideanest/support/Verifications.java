package az.ideanest.support;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a creator's identity verification into the state a test needs, by writing the row.
 *
 * <p>#431 turns {@code ideanest.verification.required} on in this profile, which means every
 * suite that reaches a payout now needs a verified creator. The issue says how to answer that:
 * "extend the fixture helper rather than each suite". This is the helper.
 *
 * <p>{@code Campaigns}' argument, unchanged. The honest way to an approval is a creator
 * uploading documents and a reviewer opening them, and {@code IdentityVerificationTests} takes
 * that path because it is what that suite is checking. A suite about dual approval on a payout
 * is not, and driving its fixture through a document review would make it depend on the
 * encryption key configuration and on a second staff account, for a state that is a
 * precondition rather than a subject.
 *
 * <p>What is written is what the review would have written: the state, the reviewer, the
 * decision time and the expiry. What is <em>not</em> written is the audit row, so a suite that
 * reads {@code audit_logs} for a verification decision must not use this.
 */
public final class Verifications {

    /** {@code VerificationProperties.approvalLife}'s default, so the row matches a real one. */
    private static final int APPROVAL_DAYS = 730;

    private Verifications() {
    }

    /** An approval that is live: the payout gate opens. */
    public static void approve(DataSource dataSource, UUID creatorId, UUID reviewerId) {
        write(dataSource, creatorId, "APPROVED", reviewerId, Instant.now().plusSeconds(APPROVAL_DAYS * 86_400L));
    }

    /**
     * An approval that has aged out.
     *
     * <p>Written as {@code APPROVED} with an expiry in the past rather than as {@code EXPIRED},
     * because that is the state the sweep has not reached yet and it is the case #431 cares
     * about: the standing is read from the comparison, not from the state, so the gate closes
     * whether or not anything has run.
     */
    public static void expired(DataSource dataSource, UUID creatorId, UUID reviewerId) {
        write(dataSource, creatorId, "APPROVED", reviewerId, Instant.now().minusSeconds(86_400L));
    }

    /** Documents sent and waiting on a reviewer. The payout holds. */
    public static void underReview(DataSource dataSource, UUID creatorId) {
        write(dataSource, creatorId, "SUBMITTED", null, null);
    }

    private static void write(
            DataSource dataSource, UUID creatorId, String state, UUID reviewerId, Instant expiresAt) {
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO identity_verifications
                            (id, user_id, state, subject_kind, reviewed_by, reviewed_at, expires_at,
                             created_at, updated_at)
                        VALUES (?, ?, ?, 'INDIVIDUAL', ?, ?, ?, now(), now())
                        ON CONFLICT (user_id) DO UPDATE
                           SET state = EXCLUDED.state,
                               reviewed_by = EXCLUDED.reviewed_by,
                               reviewed_at = EXCLUDED.reviewed_at,
                               expires_at = EXCLUDED.expires_at,
                               rejection_reason = NULL,
                               updated_at = now()
                        """,
                        UUID.randomUUID(),
                        creatorId,
                        state,
                        reviewerId,
                        reviewerId == null ? null : java.sql.Timestamp.from(Instant.now()),
                        expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
    }
}
