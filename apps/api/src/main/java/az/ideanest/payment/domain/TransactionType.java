package az.ideanest.payment.domain;

/**
 * §7.2's transaction types, verbatim.
 *
 * <p>Six values and one writer. Only {@link #CHARGE} is produced by anything today;
 * the rest are here because V41's check constraint lists them, and adding a value to
 * that constraint later is a migration over what will by then be the largest
 * financial record the platform holds.
 *
 * <p>The names are also the values stored in {@code transactions.type}, so renaming
 * one is a migration over an append-only table — which is the correct amount of
 * friction for renaming something that appears in a financial audit trail.
 */
public enum TransactionType {

    /** §9.3's R-05: the zero-or-minimal-value authorisation that validates a card. #55's. */
    VERIFICATION,

    /** §9.2's phase two: the collection at a campaign's close. The one thing built. */
    CHARGE,

    /** §9.7's reversal, full or partial. #67's. */
    REFUND,

    /** §9.8: a backer disputed a charge with their issuer. #68's. */
    CHARGEBACK,

    /** §9.8's fifth step in our favour: the dispute was resolved and the money came back. #68's. */
    CHARGEBACK_REVERSAL,

    /** §9.5's last arrow: the creator was paid. #69's. */
    PAYOUT
}
