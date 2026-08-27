package az.ideanest.verification.domain;

/**
 * Where a creator's identity check has got to — issue #105.
 *
 * <p>The set is closed and it is closed in the schema too
 * ({@code identity_verifications_state_known}). The transitions are
 * {@code IdentityVerifications}'; this enum only says which states exist.
 */
public enum VerificationState {

    /**
     * The platform asked and nothing has been submitted.
     *
     * <p>The state a verification starts in, including one a creator started themselves:
     * there is no separate "self-initiated" state, because the difference matters to nobody
     * downstream and a second starting state is a second branch in every query.
     */
    REQUESTED,

    /**
     * Documents are held and nobody has looked.
     *
     * <p>The only state in which {@code identity_documents} is expected to be non-empty for
     * long. Everything after this is a race between the reviewer and the retention sweep,
     * which is why the sweep is measured in days rather than hours.
     */
    SUBMITTED,

    /** A member of staff was satisfied. Carries an expiry — see {@code VerificationProperties}. */
    APPROVED,

    /**
     * A member of staff was not satisfied.
     *
     * <p>Resubmittable: a rejection for {@code UNREADABLE} is a photograph taken in bad
     * light, and a platform that made that terminal would be refusing creators over a
     * camera. {@code SUSPECTED_FORGERY} is the one that wants a human decision about the
     * account, and that decision is a suspension (#103) rather than a state here.
     */
    REJECTED,

    /**
     * Approved once, and the approval has aged past its life.
     *
     * <p>Distinct from {@link #REQUESTED} because it is not the same fact: this creator was
     * checked and the check is old, which is a different conversation from a creator who
     * was never checked at all.
     */
    EXPIRED
}
