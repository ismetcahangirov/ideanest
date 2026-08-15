package az.ideanest.reward.application;

/**
 * One field of an item or a reward tier was refused.
 *
 * <p>Answered as 400 with {@code code: REWARD_FIELD_INVALID} and the field name in
 * {@code meta}, so the editor can put the message beside the input rather than in a
 * banner above a form with twenty of them.
 *
 * <p>Every rule enforced through this exception is also a check constraint in
 * {@code V7}. The constraint is what holds against a support query, a bulk import,
 * and a code path that has not been written yet; this is what turns the same rule
 * into a 400 a creator can act on rather than a constraint violation and a 500.
 *
 * <p>A separate type from the project module's {@code ProjectFieldRejectedException}
 * rather than a reuse of it, because the two carry different {@code code} values and
 * a client branches on the code. Sharing the exception would mean one of the two
 * modules answering with the other's vocabulary.
 */
public class RewardFieldRejectedException extends RuntimeException {

    private final String field;

    /**
     * @param field the JSON field name as the client sent it — {@code limitQuantity},
     *     not {@code limit_quantity}. A client cannot highlight an input it has no
     *     name for
     * @param message written for the creator: what is allowed, not which constraint
     *     was violated
     */
    public RewardFieldRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
