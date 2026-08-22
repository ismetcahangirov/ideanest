package az.ideanest.admin.api;

import az.ideanest.user.application.AdministeredAccount;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One account as §4.11's AD-04 screen sees it.
 *
 * <p><strong>It carries an email address, which almost no response on this platform
 * does.</strong> That is the point of the screen — staff arrive holding a complaint and
 * an address — and it is why every read of it is audited and why the endpoint is
 * {@code no-store}. What it deliberately does not carry is anything about what the person
 * has done: their campaigns, their pledges, and their reports are other modules' and other
 * screens', and folding them in here would make one response the union of every personal
 * record the platform holds.
 *
 * @param emailVerified AD-04's "verification status", with the instant beside it: a
 *     boolean answers "is it proven" and staff looking at a fresh account are asking
 *     "when"
 * @param suspensionReason what the person was told. Shown so that a second moderator does
 *     not undo a decision without reading it
 * @param deletionScheduledAt V5's grace period. Present because an account that has asked
 *     to be deleted is one nobody should be surprised to find gone next week
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminUserResponse(
        UUID id,
        String email,
        String name,
        String slug,
        boolean emailVerified,
        Instant emailVerifiedAt,
        boolean suspended,
        Instant suspendedAt,
        UUID suspendedBy,
        String suspensionReason,
        Instant deletionScheduledAt,
        Instant createdAt) {

    public static AdminUserResponse of(AdministeredAccount account) {
        return new AdminUserResponse(
                account.id(),
                account.email().value(),
                account.name(),
                account.slug(),
                account.emailVerified(),
                account.emailVerifiedAt(),
                account.suspended(),
                account.suspendedAt(),
                account.suspendedBy(),
                account.suspensionReason(),
                account.deletionScheduledAt(),
                account.createdAt());
    }
}
