package az.ideanest.user.application;

import az.ideanest.shared.EmailAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * One account as administration sees it — §4.11's AD-04 (#104).
 *
 * <p><strong>A second projection beside {@link UserAccount}, and not a widening of
 * it.</strong> That one is what every module gets when it looks an account up, and it is
 * deliberately small; this carries an email address, a verification instant and a
 * suspension, is read by staff, and its caller writes an audit row for the read. Two
 * audiences, two contracts — and the day this gains a field, the one every module reads
 * should not.
 *
 * @param emailVerifiedAt AD-04's "verification status", as an instant rather than a
 *     boolean: staff looking at a complaint about a fresh account need to know
 *     <em>when</em> it was proven, and a boolean throws that away
 * @param suspendedAt null for an account in good standing
 * @param suspendedBy which staff account took the decision. Never the account itself —
 *     {@code users_suspension_has_another_author}
 * @param suspensionReason what the person is told, and what an appeal is answered from
 * @param deletionScheduledAt V5's grace period, shown because an account that has asked
 *     to be deleted is one a moderator should not be surprised to find gone next week.
 *     Orthogonal to the suspension: both can be set
 */
public record AdministeredAccount(
        UUID id,
        EmailAddress email,
        String name,
        String slug,
        Instant emailVerifiedAt,
        Instant suspendedAt,
        UUID suspendedBy,
        String suspensionReason,
        Instant deletionScheduledAt,
        Instant createdAt) {

    /** Whether trust and safety has stopped this account. */
    public boolean suspended() {
        return suspendedAt != null;
    }

    /** Whether the address has been proven — §4.11's AD-04. */
    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }
}
