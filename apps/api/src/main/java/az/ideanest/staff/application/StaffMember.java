package az.ideanest.staff.application;

import az.ideanest.shared.access.StaffCapability;
import az.ideanest.staff.domain.StaffRole;
import java.util.Set;
import java.util.UUID;

/**
 * What one account may do here, and on what basis — #295.
 *
 * <p>Both halves are returned because the console needs both and they answer different
 * questions. {@link #capabilities()} decides what the screen renders; {@link #roles()}
 * is what a person is told when they ask why, and what an administrator changes.
 * Deriving one from the other in the browser would put V48's policy in two places.
 *
 * @param accountId whoever is signed in
 * @param roles the grants standing against this account, empty for a bootstrap
 *     administrator — see {@link #bootstrapped()}
 * @param capabilities the union of those roles' sets
 * @param bootstrapped whether this account is staff by configuration rather than by a
 *     grant. Rendered on the console's staff screen as a warning, because an
 *     administrator who exists only in an environment variable is one nobody can
 *     withdraw through the platform
 */
public record StaffMember(
        UUID accountId, Set<StaffRole> roles, Set<StaffCapability> capabilities, boolean bootstrapped) {

    public StaffMember {
        roles = Set.copyOf(roles);
        capabilities = Set.copyOf(capabilities);
    }

    /** Whether this account works here at all. Empty capabilities is not staff. */
    public boolean isStaff() {
        return !capabilities.isEmpty();
    }

    /** Whether this account holds a particular authority. */
    public boolean holds(StaffCapability capability) {
        return capabilities.contains(capability);
    }

    /** Nobody: the answer for an account with no grants and no bootstrap entry. */
    public static StaffMember none(UUID accountId) {
        return new StaffMember(accountId, Set.of(), Set.of(), false);
    }
}
