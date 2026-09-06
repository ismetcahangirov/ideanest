package az.ideanest.support;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a creator's payout destination into the state a test needs, by writing the row.
 *
 * <p>{@link Verifications}' twin, and it exists for the same reason one issue along. #432 gates
 * a payout on a destination somebody confirmed, so every suite that reaches an approval now
 * needs one — and #431 already settled how to answer that: "extend the fixture helper rather
 * than each suite".
 *
 * <p>What is written is what the creator and the reviewer between them would have written: the
 * provider, the token, the holder's name and the verification. What is <strong>not</strong>
 * written is the audit row, so a suite reading {@code audit_logs} for a destination decision
 * must drive {@code CreatorPayoutDestinations} instead of using this.
 *
 * <p>The reference is obviously fake, and deliberately so. A real one is a token from a
 * provider adapter, no adapter exists until #433, and a fixture that dressed a made-up string
 * up as a plausible IBAN would be the one thing {@code PayoutRequest} says the platform must
 * never hold.
 */
public final class Destinations {

    /** What the fixture files against, unless a suite is testing the mismatch. */
    public static final String PROVIDER = "EPOINT";

    private Destinations() {
    }

    /** A destination somebody confirmed: the payout gate opens. */
    public static void verified(DataSource dataSource, UUID creatorId, UUID verifierId, String holderName) {
        write(dataSource, creatorId, PROVIDER, holderName, "VERIFIED", null, "STAFF_ATTESTED", verifierId);
    }

    /** A destination filed against a provider this deployment does not send through. */
    public static void verifiedWith(
            DataSource dataSource, UUID creatorId, UUID verifierId, String provider, String holderName) {
        write(dataSource, creatorId, provider, holderName, "VERIFIED", null, "STAFF_ATTESTED", verifierId);
    }

    /** Filed, and nobody has looked at it. The payout holds. */
    public static void awaiting(DataSource dataSource, UUID creatorId, String holderName) {
        write(dataSource, creatorId, PROVIDER, holderName, "AWAITING_VERIFICATION", null, null, null);
    }

    /** The account is held by somebody else. The payout holds and a reviewer cannot wave it through. */
    public static void nameMismatch(DataSource dataSource, UUID creatorId, String holderName) {
        write(dataSource, creatorId, PROVIDER, holderName, "NAME_MISMATCH", null, null, null);
    }

    /** Refused by a reviewer. */
    public static void rejected(DataSource dataSource, UUID creatorId, String holderName, String reason) {
        write(dataSource, creatorId, PROVIDER, holderName, "REJECTED", reason, null, null);
    }

    /** Removes every destination, for a suite that truncates around itself. */
    public static void clear(DataSource dataSource) {
        new JdbcTemplate(dataSource).update("DELETE FROM payout_destinations");
    }

    private static void write(
            DataSource dataSource,
            UUID creatorId,
            String provider,
            String holderName,
            String state,
            String rejectionReason,
            String method,
            UUID verifierId) {

        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO payout_destinations
                            (user_id, provider, reference, holder_name, display_hint, state,
                             rejection_reason, verification_method, verified_at, verified_by,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, '**4321', ?, ?, ?,
                                CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, ?, now(), now())
                        ON CONFLICT (user_id) DO UPDATE
                           SET provider = EXCLUDED.provider,
                               reference = EXCLUDED.reference,
                               holder_name = EXCLUDED.holder_name,
                               state = EXCLUDED.state,
                               rejection_reason = EXCLUDED.rejection_reason,
                               verification_method = EXCLUDED.verification_method,
                               verified_at = EXCLUDED.verified_at,
                               verified_by = EXCLUDED.verified_by,
                               updated_at = now()
                        """,
                        creatorId,
                        provider,
                        "test-destination-token-" + creatorId,
                        holderName,
                        state,
                        rejectionReason,
                        method,
                        method,
                        verifierId);
    }
}
