package az.ideanest.payment.application;

/**
 * One thing the reconciliation found wrong — issue #70.
 *
 * <p>A finding is a sentence somebody has to act on, not a code somebody has to look up. The
 * kind is what a metric counts and an alert routes on; the detail is what the person woken by
 * that alert reads first, and it carries the figures rather than pointing at a query they
 * would have to write.
 *
 * <p><strong>No account identifier, no campaign, no amount as a separate field.</strong> They
 * are in the detail where they belong: this is a log line and a health row, not a table
 * somebody joins against, and the moment it grows columns it becomes a schema to migrate.
 *
 * @param kind which of {@link LedgerReconciliation}'s three questions was answered wrongly
 * @param currency the currency the discrepancy is in. §21.2 refuses to add two, so a finding
 *     is always about exactly one
 * @param detail what is wrong, with the numbers in it
 */
public record ReconciliationFinding(Kind kind, String currency, String detail) {

    /** What kind of thing went wrong, which is what an alert is routed on. */
    public enum Kind {
        /** The debits do not equal the credits. V41's trigger should make this impossible. */
        UNBALANCED,
        /** An account holds a balance whose sign cannot be true — see the checks. */
        IMPOSSIBLE_SIGN,
        /** The ledger and the transaction records disagree about what the platform holds. */
        DISAGREES_WITH_PAYMENTS
    }
}
