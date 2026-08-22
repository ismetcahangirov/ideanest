package az.ideanest.payment.application;

/**
 * What one call to {@code CollectionRun#collectNext} came to.
 *
 * <p>Six values, and the pass reads them for two different decisions: whether anything
 * happened, and whether to carry on. They are not the same question —
 * {@link #DECLINED} means something happened and the pass should continue, while
 * {@link #NOTHING_DUE} and {@link #PROVIDER_UNAVAILABLE} both mean stop for opposite
 * reasons.
 */
public enum CollectionOutcome {

    /**
     * No provider is configured, so nothing was looked at.
     *
     * <p>The shipped state of every deployed environment: #60 has not chosen one and
     * §9.2 refuses a stub. The pass logs once and returns.
     */
    NO_PROVIDER,

    /** This queue has nothing due. The pass is finished, and that is the ordinary end of one. */
    NOTHING_DUE,

    /** A card was charged. */
    COLLECTED,

    /** A card was refused. The pass carries on: the next pledge is somebody else's card. */
    DECLINED,

    /**
     * The provider took the instruction and has not decided, so the same attempt will be
     * asked about again. The pass carries on.
     */
    UNRESOLVED,

    /**
     * The provider could not be reached, or its breaker is open.
     *
     * <p><strong>The pass stops on this one.</strong> Every remaining pledge would meet
     * the same outage, and the difference between stopping and not is four thousand
     * pointless requests at a provider that is already struggling — and four thousand
     * rows in {@code transactions} recording that the platform could not ask.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The attempt could not be made for a reason that is the platform's own: a campaign
     * that has gone, a currency that does not match. Logged at {@code ERROR}, the pledge
     * left where it was, and the pass carries on — it is one pledge's problem and not the
     * queue's.
     */
    FAILED;

    /** Whether the pass should look for another pledge. */
    public boolean continuesThePass() {
        return this == COLLECTED || this == DECLINED || this == UNRESOLVED || this == FAILED;
    }
}
