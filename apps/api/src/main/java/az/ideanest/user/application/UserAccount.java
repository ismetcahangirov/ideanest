package az.ideanest.user.application;

import az.ideanest.shared.EmailAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * What another module is told about a user.
 *
 * <p>Not the entity. The entity is this module's internal shape and is free to
 * change; handing it out would make every field a published contract and every
 * caller a reason not to change one. It would also hand out a live JPA
 * instance, which another module could then modify outside this module's
 * transaction.
 *
 * @param deletionScheduledAt when this account is due to be anonymised, or null
 *     if nobody has asked for that. Part of the contract because an account in
 *     its grace period may sign in and may do almost nothing else, and every
 *     caller that decides whether an action is allowed needs to see it.
 * @param suspendedAt when trust and safety stopped this account, or null — §4.11's
 *     AD-04 (#104). Part of the contract for the same reason as the field above and one
 *     stronger: a suspended account may not sign in at all, and sign-in is in another
 *     module. Orthogonal to deletion, so both can be set at once
 */
public record UserAccount(
        UUID id,
        EmailAddress email,
        String name,
        String slug,
        boolean emailVerified,
        Instant deletionScheduledAt,
        Instant suspendedAt) {

    /** Whether this account is inside its grace period and awaiting anonymisation. */
    public boolean deletionPending() {
        return deletionScheduledAt != null;
    }

    /** Whether trust and safety has stopped this account. What sign-in refuses on. */
    public boolean suspended() {
        return suspendedAt != null;
    }
}
