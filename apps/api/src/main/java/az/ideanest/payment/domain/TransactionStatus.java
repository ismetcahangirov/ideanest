package az.ideanest.payment.domain;

/**
 * What a provider call came to, frozen at insert.
 *
 * <p><strong>There are no transitions between these values</strong>, and that is not
 * an omission. V41 makes {@code transactions} append-only, so a row is written
 * carrying the outcome that was known at the time and never moves; a {@link #PENDING}
 * charge that a webhook later resolves is a <em>new</em> row. §7.2's "corrections are
 * new rows" is the same rule stated for a different case.
 *
 * <p>The mapping from {@link ProviderOutcome} is {@link #of}, and it lives here rather
 * than at each call site so that the one dangerous mistake in this feature — recording
 * an approval as anything else, or anything else as an approval — has one place to be
 * made.
 */
public enum TransactionStatus {

    /**
     * The provider accepted the instruction and has not decided.
     *
     * <p><strong>Not a synonym for "we do not know".</strong> A call that timed out
     * before the provider answered is {@link #FAILED} with
     * {@code PaymentTransaction.UNREACHABLE}, because the difference is whether the
     * instruction was received — and therefore whether retrying it might charge
     * somebody twice.
     */
    PENDING,

    /** Money moved. The only status that has a ledger posting beside it. */
    SUCCEEDED,

    /**
     * It did not. Covers a card the issuer refused and a provider that could not be
     * reached, told apart by {@code failure_code} rather than by a seventh status:
     * every consumer of this column asks "did this move money", and a status that
     * split the two would make that question a set membership test.
     */
    FAILED;

    /** What a provider's answer means for the row. See the class comment. */
    public static TransactionStatus of(ProviderOutcome outcome) {
        return switch (outcome) {
            case APPROVED -> SUCCEEDED;
            case DECLINED -> FAILED;
            case PENDING -> PENDING;
        };
    }
}
