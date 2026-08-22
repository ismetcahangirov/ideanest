package az.ideanest.ledger.application;

import java.util.UUID;

/**
 * A posting whose debits and credits do not agree.
 *
 * <p><strong>A programming error, and thrown where the mistake is.</strong> V41's
 * deferred constraint trigger is what makes the invariant true of the database, but
 * it fires at {@code COMMIT} and so reports the failure at the commit — after the
 * transaction has done everything else it was going to do, with a stack that names no
 * line anybody wrote. {@link Posting} therefore refuses first, at the
 * {@code build()} that produced the imbalance.
 *
 * <p>Unchecked, and deliberately not caught anywhere. There is no sensible recovery
 * from "the platform's own arithmetic does not add up": the correct outcome is that
 * the transaction rolls back, the collection attempt is recorded as having failed,
 * and somebody reads the log. Catching it to carry on would be posting half a
 * movement of money.
 */
public class UnbalancedPostingException extends RuntimeException {

    private final UUID transactionId;

    public UnbalancedPostingException(UUID transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    /** Which transaction failed to balance. Null only if the posting had no transaction at all. */
    public UUID transactionId() {
        return transactionId;
    }
}
