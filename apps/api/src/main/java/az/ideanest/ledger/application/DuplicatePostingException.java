package az.ideanest.ledger.application;

import java.util.UUID;

/**
 * Something has already been posted against this transaction.
 *
 * <p>Like {@code UnbalancedPostingException}, a programming error rather than a
 * condition to recover from — and the failure it names is the more dangerous of the
 * two, because a second posting <em>balances</em>. A campaign whose escrow is twice
 * what was collected passes V41's trigger, passes reconciliation's per-transaction
 * check, and is only ever caught by somebody comparing the platform's books against
 * the provider's statement.
 *
 * <p>{@code transactions.idempotency_key} is what stops the same provider call being
 * recorded twice, and it cannot see this: the duplicate here is one caller posting
 * twice against a transaction row it is already holding.
 */
public class DuplicatePostingException extends RuntimeException {

    private final UUID transactionId;

    public DuplicatePostingException(UUID transactionId) {
        super("Transaction " + transactionId + " already has ledger entries; a correction is a reversing posting");
        this.transactionId = transactionId;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
