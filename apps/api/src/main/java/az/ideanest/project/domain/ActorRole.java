package az.ideanest.project.domain;

/**
 * In what capacity somebody changed a campaign's state.
 *
 * <p>Recorded on every transition rather than inferred from the actor. The same
 * account can be a creator on one campaign and staff on another, and "who
 * approved this" is answerable a year later only if the row says which of the
 * two they were acting as at the time.
 */
public enum ActorRole {

    /** The account that owns the campaign. */
    CREATOR,

    /**
     * Somebody the creator granted a capability to. No transition carries this
     * role yet — #38 introduces collaborators — and it exists here because the
     * column's check constraint has to accept it before the rows that use it
     * arrive, and because a role added later would be a migration.
     */
    COLLABORATOR,

    /** Platform staff, acting through the admin API. */
    MODERATOR,

    /**
     * A scheduled job: a launch date arriving, a deadline passing. The only role
     * that may be recorded without an actor, because there is no person to
     * record.
     */
    SYSTEM
}
