package az.ideanest.subscription.application;

/**
 * The account already holds a subscription that has not ended.
 *
 * <p>V62 permits one open subscription per account, and this is that rule as a sentence.
 * It covers both a live plan and one waiting for its payment to be recorded, which the
 * message distinguishes: "you are on Growth until 3 September" and "you chose Growth and
 * we have not seen the transfer yet" lead a creator to different next steps.
 *
 * <p>Changing plan is therefore cancel-then-subscribe rather than an upgrade endpoint.
 * That is a deliberate limit and the design records it: proration needs a provider that
 * can refund a part-month, and #60 is unanswered.
 */
public class AlreadySubscribedException extends RuntimeException {

    private final boolean awaitingPayment;

    public AlreadySubscribedException(boolean awaitingPayment) {
        super(awaitingPayment
                ? "This account has already chosen a plan and is waiting for payment to be recorded"
                : "This account already holds a subscription");
        this.awaitingPayment = awaitingPayment;
    }

    /** Whether what is in the way is a pending purchase rather than a running one. */
    public boolean awaitingPayment() {
        return awaitingPayment;
    }
}
