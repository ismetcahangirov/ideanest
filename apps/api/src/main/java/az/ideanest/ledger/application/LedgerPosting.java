package az.ideanest.ledger.application;

import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One posting: everything the ledger wrote about a single transaction, together.
 *
 * <p><strong>This is the unit AD-05 asks for, and the row is not.</strong> §7.2's invariant
 * is stated per transaction — for every {@code transaction_id}, the debits equal the
 * credits — so a screen that lists entries is a screen on which the platform's central
 * accounting rule is invisible. Grouping here means a reader can see the rule hold, or see
 * it fail, which is what a ledger explorer is for.
 *
 * @param transactionId the provider call these entries explain. Joins to the payment log
 *     (#304), which is the other half of the same event: what was asked of a provider, and
 *     what it meant
 * @param projectId whose money moved. Present on every entry of the posting, and therefore
 *     once here
 * @param createdAt when the first of the entries was written
 * @param lines both sides, in the order they were written. Always at least two — a
 *     one-sided posting cannot exist, because {@code Posting} refuses to build one and
 *     V41's deferred constraint trigger refuses to commit one
 * @param balanced whether the debits equal the credits. Computed rather than assumed:
 *     the database enforces it and this is where somebody reading the screen can see that
 *     it did. A false here is a defect worth an incident, not a rounding note
 */
public record LedgerPosting(
        UUID transactionId, UUID projectId, Instant createdAt, List<Line> lines, boolean balanced) {

    public LedgerPosting {
        lines = List.copyOf(lines);
    }

    /**
     * One side of one posting.
     *
     * @param account which of §7.2's six, as it is stored
     * @param direction debit or credit
     * @param amount always positive; the direction carries the sign, which is why an
     *     amount on this platform is never negative and never has to be checked for it
     */
    public record Line(String account, EntryDirection direction, Money amount) {
    }
}
