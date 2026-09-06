package az.ideanest.shared.compliance;

/**
 * Where a creator stands with a payout destination, as the payout gate needs to see it —
 * part of issue #432.
 *
 * <p>{@link VerificationStanding}'s shape and for the same reason, one question along.
 * That one answers "has the platform established who this creator is"; this one answers
 * "has the platform established that the account it is about to send money to is theirs".
 * They are separate because they fail separately: a creator whose passport was approved in
 * March can still file a bank account in June that belongs to somebody else, and a single
 * value covering both would let the second pass on the strength of the first.
 *
 * <p><strong>There is no {@code NOT_REQUIRED}.</strong> {@link VerificationStanding} has
 * one because #424 has not set the threshold at which identity must be verified, and
 * inventing one would be a compliance position this repository made up. No equivalent
 * question exists here: sending money to an account nobody confirmed belongs to the
 * recipient is not a policy the platform could reasonably adopt at any threshold, and the
 * bounded exception for the case nobody anticipated is #436's override, which expires.
 */
public enum DestinationStanding {

    /**
     * A member of staff waived the requirement for this creator, for a bounded time.
     *
     * <p>#436's {@code ComplianceRequirement.PAYOUT_DESTINATION}, which V66 defined one
     * issue before there was anything to waive. Distinct from {@link #VERIFIED} rather than
     * folded into it, on {@code VerificationStanding.WAIVED}'s argument: the console must
     * not draw a waiver as though somebody had checked an account.
     *
     * <p>It is the more dangerous of the two waivers this epic added, because what it
     * excuses is the check standing between an approved payout and an account belonging to
     * whoever supplied the token. An override still cannot supply a destination — a payout
     * with no row at all has nothing to send to and is refused whatever the override says.
     */
    WAIVED,

    /** Confirmed to belong to the creator. Money may leave. */
    VERIFIED,

    /** The creator has recorded nothing. There is nowhere to send money to. */
    NONE,

    /** Recorded, and nobody has confirmed yet that the account is the creator's. */
    AWAITING_VERIFICATION,

    /**
     * The account holder's name is not the creator's legal name.
     *
     * <p>Its own value rather than a rejection, because it is the one refusal the creator
     * can often resolve themselves — a legal name recorded with a missing patronymic, a
     * company account filed by a creator registered as an individual — and because it is
     * also the exact shape of somebody filing another person's account. Those need to be
     * distinguishable to the reviewer looking at them, which a shared "refused" would not
     * be.
     */
    NAME_MISMATCH,

    /** A reviewer refused it, and {@code payout_destinations.rejection_reason} says why. */
    REJECTED;

    /** Whether a payout may move on the strength of this standing. */
    public boolean releasesPayout() {
        return this == WAIVED || this == VERIFIED;
    }

    /**
     * Whether the creator has something to do about it.
     *
     * <p>{@link #AWAITING_VERIFICATION} is deliberately not in the set: the creator has done
     * their part and the platform is the one that owes an answer, so telling them to act
     * would be telling them to file the account they have already filed.
     */
    public boolean asksTheCreatorForSomething() {
        return this == NONE || this == NAME_MISMATCH || this == REJECTED;
    }
}
