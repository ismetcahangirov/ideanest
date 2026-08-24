package az.ideanest.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Who acted, and on whose behalf if they were impersonating somebody.
 *
 * <p><strong>A type rather than a bare {@code UUID} argument, so that the actor and
 * the thing acted on cannot be swapped.</strong> Both are account identifiers on
 * half the actions here — {@code audit.record(TWO_FACTOR_DISABLED, userId,
 * AuditActor.user(userId), …)} names the same account twice, and the version with
 * the arguments the wrong way round would compile, run, and record a lie. It cannot
 * be written this way.
 *
 * <h2>Impersonation</h2>
 *
 * <p>{@link #onBehalfOf} is modelled and is never set by this release. AD-04's
 * "audited impersonation" is epic #100's (#104), and the record has to exist before
 * the feature does: impersonation shipped against a log with nowhere to put the
 * subject would mean a member of staff acting as a backer and the row saying only
 * that the backer acted — which is worse than no record, because it is a record
 * that reads as exculpatory.
 *
 * <p>Building the field now costs one nullable column and this method. Building it
 * afterwards costs those plus a permanent ambiguity: every row written before would
 * be indistinguishable from a genuine non-impersonation.
 *
 * @param type what kind of thing acted
 * @param id which account, or null for {@link AuditActorType#SYSTEM}
 * @param onBehalfOf the account being impersonated, or null — which is every row
 *     this release can write
 */
public record AuditActor(AuditActorType type, UUID id, UUID onBehalfOf) {

    public AuditActor {
        Objects.requireNonNull(type, "An action was taken by some kind of actor");

        // The same two rules V21 holds as check constraints. Here as well, and not
        // instead: this catches the caller, with a stack trace naming the call site,
        // and the constraint catches the migration, the support query and the bug.
        if ((type == AuditActorType.SYSTEM) != (id == null)) {
            throw new IllegalArgumentException(
                    type == AuditActorType.SYSTEM
                            ? "Scheduled work has no account behind it"
                            : "An account acted, so the account is part of the record");
        }
        if (onBehalfOf != null && onBehalfOf.equals(id)) {
            throw new IllegalArgumentException("Acting as oneself is not impersonation");
        }
        if (onBehalfOf != null && id == null) {
            throw new IllegalArgumentException("Somebody was impersonated, so somebody did the impersonating");
        }
    }

    /** An account acting as itself. */
    public static AuditActor user(UUID id) {
        return new AuditActor(AuditActorType.USER, Objects.requireNonNull(id, "An account acted"), null);
    }

    /**
     * An account acting with platform authority.
     *
     * <p>Called from the paths that have already asked {@code PlatformStaff}
     * whether this account may moderate. This type records the answer; it does not
     * decide it, and a call site that used it without the check would be recording
     * an authority it never verified.
     */
    public static AuditActor moderator(UUID id) {
        return new AuditActor(AuditActorType.MODERATOR, Objects.requireNonNull(id, "A moderator acted"), null);
    }

    /** Scheduled work: a sweep, a job, anything with no person behind it. */
    public static AuditActor system() {
        return new AuditActor(AuditActorType.SYSTEM, null, null);
    }

    /**
     * The same actor, acting as somebody else.
     *
     * <p>Unused until #104. It exists so that the feature is a call to this method
     * rather than a migration, a backfill, and an argument about what the rows
     * written in between meant.
     */
    public AuditActor onBehalfOf(UUID subjectId) {
        return new AuditActor(type, id, Objects.requireNonNull(subjectId, "An impersonation has a subject"));
    }
}
