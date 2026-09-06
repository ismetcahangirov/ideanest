package az.ideanest.shared.compliance;

/**
 * Where a creator stands with identity verification, as the payout gate needs to see it —
 * issue #431.
 *
 * <p>{@code VerificationState} is the state machine of one verification row. This is the
 * answer to a different question: <em>may money leave to this person, and if not, what is
 * the creator waiting for?</em> The two are not the same set, because two of the values
 * below come from outside the row entirely — {@link #NOT_REQUIRED} from configuration and
 * {@link #WAIVED} from #436's override — and one of them, {@link #NEVER_REQUESTED}, is the
 * state of a creator who has no row at all.
 *
 * <p><strong>Every value that is not {@link #VERIFIED} still has to be distinguishable.</strong>
 * V55's argument, which #431 quotes: a payout that will not approve, shown to a finance
 * operator as a payout that will not approve, "makes the same campaign look as though nothing
 * is owed". A single boolean would do that. So the console draws the value, and the creator
 * is told which of these they are in.
 */
public enum VerificationStanding {

    /**
     * {@code ideanest.verification.required} is off, so nothing is gated on identity.
     *
     * <p>The state the platform is in until #424 answers, and the reason this whole mechanism
     * can ship before the answer arrives: V58 built the machinery and turned it off, #431
     * wires it up, and the threshold that turns it on is a configuration change rather than a
     * release. A gate that read {@code false} as "refuse" would have decided the compliance
     * position the flag exists not to decide.
     */
    NOT_REQUIRED,

    /**
     * A member of staff waived the requirement for this creator, for a bounded time.
     *
     * <p>#436's {@code ComplianceRequirement.IDENTITY_VERIFICATION}. Distinct from
     * {@link #VERIFIED} rather than folded into it, because the two are answers to "may this
     * payout go" that a regulator would read very differently, and the console must not draw
     * a waiver as though somebody had checked a document.
     */
    WAIVED,

    /** An approval that has not aged out. Money may leave. */
    VERIFIED,

    /** Nobody has asked this creator for anything yet. #431 is what asks. */
    NEVER_REQUESTED,

    /** Asked, and the creator has not sent documents. */
    AWAITING_DOCUMENTS,

    /** Sent, and waiting on a reviewer. The fourteen-day hold is where this fits. */
    UNDER_REVIEW,

    /** A reviewer refused it. {@code identity_verifications.rejection_reason} says why. */
    REJECTED,

    /**
     * An approval that has aged out.
     *
     * <p><strong>Not a failure, and the distinction is the point of the value existing.</strong>
     * #431: "An approval that has aged out is not a rejection: the payout waits and the creator
     * is asked to re-verify. Failing it permanently would strand money that is owed, over a
     * document that was fine last year."
     */
    EXPIRED;

    /** Whether a payout may move out of §6.3's hold on the strength of this standing. */
    public boolean releasesPayout() {
        return this == NOT_REQUIRED || this == WAIVED || this == VERIFIED;
    }

    /**
     * Whether the creator has something to do about it.
     *
     * <p>What #431's "the creator is notified and asked for documents" keys off. A rejection is
     * in this set — a refused document is re-submittable, and the reviewer's reason says what
     * to send instead — and {@link #UNDER_REVIEW} is not, because asking somebody to send
     * documents they have already sent is how a queue gets four copies of the same passport.
     */
    public boolean asksTheCreatorForSomething() {
        return this == NEVER_REQUESTED || this == AWAITING_DOCUMENTS || this == REJECTED || this == EXPIRED;
    }
}
