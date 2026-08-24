package az.ideanest.payment.domain;

/**
 * Where a refund has got to — V53, issues #67 and #307.
 *
 * <p><strong>Three states, and there is deliberately no {@code CANCELLED}.</strong> A
 * refund that has not been sent is not cancelled by anybody; it simply stays
 * {@link #REQUESTED} until the sender picks it up. A state meaning "we changed our mind"
 * would be indistinguishable, on the screen and in the table, from one meaning "the job
 * has not run yet" — and the difference between those two is whether somebody is owed
 * money.
 *
 * <p>{@link #FAILED} is terminal. A retry is a new row, because it is a new decision and
 * because replaying the same idempotency key would ask the provider to repeat a call it
 * has already refused.
 */
public enum RefundState {

    /** Decided and recorded, not yet sent. */
    REQUESTED,

    /**
     * The provider accepted it.
     *
     * <p>Accepted rather than settled: {@code RefundResult}'s comment notes that most
     * providers take a refund immediately and settle it over the following days. The
     * ledger posting is written at acceptance, which is when the platform's obligation
     * becomes real — waiting for settlement would leave the books saying the money is
     * still the campaign's while the provider is already sending it back.
     */
    SUCCEEDED,

    /** The provider refused, or could not be reached. Terminal for this row. */
    FAILED
}
