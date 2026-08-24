package az.ideanest.audit;

/**
 * What kind of thing performed a privileged action.
 *
 * <p>Three values, and the set is closed by a check constraint in V21 as well as by
 * this enum. Not redundant with the actor's identifier: {@link #SYSTEM} has none,
 * and the first question asked of any row is whether a person or a timer was
 * responsible.
 */
public enum AuditActorType {

    /** An account acting as itself. */
    USER,

    /**
     * An account acting with platform authority.
     *
     * <p>Separate from {@link #USER} because staff authority is the authority worth
     * counting. Until epic #100 gives the platform a role model there is no role to
     * read — since #295 a granted role in {@code staff_role_grants} is what decides
     * it — so this is recorded by the call site that already asked
     * {@code PlatformStaff}, at the point where it knows.
     */
    MODERATOR,

    /**
     * Scheduled work, with no person behind it.
     *
     * <p>Carries no identifier, and V21 refuses a row that pairs it with one: a
     * sweep recorded as an account is a decision attributed to somebody who was
     * asleep.
     */
    SYSTEM
}
