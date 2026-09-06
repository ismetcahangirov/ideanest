package az.ideanest.compliance.domain;

/**
 * How the platform established that a payout destination belongs to the creator it is filed
 * under — part of issue #432.
 *
 * <p><strong>Recorded rather than assumed, because the three are not equally strong and the
 * difference matters years later.</strong> #432 sets out two mechanisms and asks that the
 * row carry "a recorded note of which mechanism verified it and when". A column that said
 * only "verified" would answer the question an investigation asks with the least useful of
 * three possible truths.
 *
 * <p>Two of the three cannot run yet. That is not an oversight and they are values here
 * anyway: the enum's job is to describe what a stored row means, and a row written by the
 * micro-transfer once it exists must not be indistinguishable from one a person attested to
 * by eye.
 */
public enum DestinationVerificationMethod {

    /**
     * The provider returned a verified account holder name and it matched.
     *
     * <p>#432's mechanism A, and the strongest of the three: the bank validated the account
     * during sub-merchant onboarding, so the platform is reading somebody else's completed
     * work rather than performing its own.
     *
     * <p><strong>Nothing writes this yet.</strong> It depends on §9.3's R-10 — whether a
     * creator can be onboarded as an Epoint sub-merchant, and whether that onboarding
     * includes bank-account validation — which is #422's to answer in writing.
     */
    PROVIDER_ACCOUNT_HOLDER,

    /**
     * The creator sent a token amount from the account, and the statement line named them.
     *
     * <p>#432's mechanism B. Stronger than what most platforms do, because it cannot be
     * satisfied by somebody who merely knows another person's account number: the incoming
     * statement line carries the sender's IBAN and registered name, so the bank performs the
     * verification and the platform reads the result.
     *
     * <p><strong>Nothing writes this yet either.</strong> It needs a statement-matching
     * surface and, to be automatic, a bank feed. {@link #STAFF_ATTESTED} is what the manual
     * form of the same evidence is recorded as until then.
     */
    MICRO_TRANSFER,

    /**
     * A compliance reviewer looked at the evidence and said it is theirs.
     *
     * <p>The weakest of the three and the only one available today. It is honest about what
     * it is: a person's judgement about documents, recorded with their name on it, which is
     * what #432 describes mechanism B degenerating into before it is automated — "a manual
     * or semi-automated statement reconciliation, which at launch volumes is a few minutes a
     * day and later is a bank feed".
     *
     * <p><strong>It is not a member of staff typing a destination.</strong> That is the
     * practice this issue exists to end, and the difference is not cosmetic: the creator
     * supplied the account, the reviewer holds {@code VERIFY_PAYOUT_DESTINATION} rather than
     * {@code APPROVE_PAYOUT}, and the attestation is a row with an actor on it rather than a
     * field on a request nobody kept.
     */
    STAFF_ATTESTED
}
