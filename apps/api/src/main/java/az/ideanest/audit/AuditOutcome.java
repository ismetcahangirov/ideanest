package az.ideanest.audit;

/**
 * How a privileged action came out.
 *
 * <p><strong>Three values rather than a boolean, because the interesting row is
 * usually not the one that worked.</strong> "Who switched off this account's second
 * factor" is answered by a table of successes; "who spent an afternoon trying to"
 * is not, and it is the question an incident starts from.
 *
 * <p>{@link #REFUSED} is also the reason {@link AuditLog} has two entry points. A
 * refusal is normally recorded by a transaction that is about to throw, and a row
 * written inside it would be rolled back by the very refusal it describes.
 */
public enum AuditOutcome {

    /** The action was permitted and it happened. */
    SUCCEEDED,

    /**
     * The platform declined it: a caller without the authority, a wrong password, a
     * rule that says no. Nothing changed, and somebody tried.
     */
    REFUSED,

    /**
     * It was permitted and did not complete — a downstream call that failed, a
     * transport that was not there.
     *
     * <p>Distinct from {@link #REFUSED} because the two send an investigation in
     * opposite directions: one is a person to talk to, the other is a system to
     * fix.
     */
    FAILED
}
