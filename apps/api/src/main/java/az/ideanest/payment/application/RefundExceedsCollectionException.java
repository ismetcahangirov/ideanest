package az.ideanest.payment.application;

import az.ideanest.shared.money.Money;
import java.util.UUID;

/**
 * The refund would return more than was taken — #67.
 *
 * <p>V53's header names this as the invariant it cannot express as a {@code CHECK}: it is
 * a statement about a set of rows in {@code refunds} joined against a set of rows in
 * {@code transactions}. So it is enforced in {@code RefundRecords} under one transaction,
 * and this is what comes out.
 *
 * <p><strong>The message names what is left</strong>, because the usual cause is not a
 * mistake — it is a second partial refund against a pledge that already had one, issued
 * from a screen that was loaded before the first. Telling somebody "too much" without
 * telling them the number means they try again with a guess.
 */
public class RefundExceedsCollectionException extends RuntimeException {

    private final transient Money remaining;

    public RefundExceedsCollectionException(UUID pledgeId, Money requested, Money remaining) {
        super("Refund of " + requested + " on pledge " + pledgeId + " exceeds the " + remaining + " remaining");
        this.remaining = remaining;
    }

    /** What could still be refunded, for the message the console shows. */
    public Money remaining() {
        return remaining;
    }
}
