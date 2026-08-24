package az.ideanest.payment.domain;

/**
 * Where a chargeback has got to — V54, issues #68 and #308.
 *
 * <p><strong>This state machine has a cycle, and a refund's does not.</strong> A dispute
 * can be lost, won on representment, and lost again on a second presentment — so there is
 * no constraint anywhere asserting that a dispute only moves forward, and any code that
 * assumes one is wrong about how card networks work.
 *
 * <p>The distinction between {@link #LOST} and {@link #CONCEDED} is not cosmetic. Both end
 * with the backer having their money; only one of them is a decision the platform made,
 * and the ratio between them is what tells anybody whether contesting is worth the effort.
 */
public enum DisputeState {

    /** The provider has notified us and nobody has answered. The front of the queue. */
    OPEN,

    /** Evidence is in and the network has not decided. */
    UNDER_REVIEW,

    /** The charge stands. */
    WON,

    /** The network decided against us. */
    LOST,

    /**
     * We chose not to contest it.
     *
     * <p>Usually paired with a {@code refunds} row carrying {@code DISPUTE_CONCEDED} —
     * though not always, because a provider sometimes accepts the reversal before anybody
     * presses anything. V54's header has why there is no foreign key between the two.
     */
    CONCEDED;

    /** Whether the case is over. The three terminal states carry a {@code resolvedAt}. */
    public boolean isResolved() {
        return this == WON || this == LOST || this == CONCEDED;
    }
}
