package az.ideanest.reward.application;

/**
 * An edit touched something §5.3 froze when the campaign launched.
 *
 * <p>Answered as 409 with {@code code: REWARD_FIELD_LOCKED}, which is the project
 * module's {@code PROJECT_FIELD_LOCKED} with this module's vocabulary — the same
 * split, and for the same reason, as {@link RewardFieldRejectedException} against
 * {@code ProjectFieldRejectedException}. A client branches on the code, and one
 * module answering in the other's would make the two indistinguishable in a log and
 * in a switch.
 *
 * <p><strong>409 rather than the 400 a rejected field gets.</strong> A price of
 * {@code "-1.00"} is not a price and never will be; a price of {@code "24.99"} is a
 * perfectly good one that the campaign's state refuses. The client's next move
 * differs: fix the value, against reload the campaign.
 *
 * <p>The state arrives as a string because it belongs to another module's domain
 * and this one may not name it. See {@code EditLocks}, which is where that
 * translation happens once.
 */
public class RewardFieldLockedException extends RuntimeException {

    private final String field;
    private final String state;

    /**
     * @param field the JSON key as the client sent it — {@code limitQuantity}, so the
     *     editor can disable the input that caused it
     * @param message written for the creator: what §5.3 allows now, not which rule
     *     was consulted
     * @param state the campaign's state, which is what refused the edit and what the
     *     client needs in order to explain it
     */
    public RewardFieldLockedException(String field, String state, String message) {
        super(message);
        this.field = field;
        this.state = state;
    }

    public String field() {
        return field;
    }

    public String state() {
        return state;
    }
}
