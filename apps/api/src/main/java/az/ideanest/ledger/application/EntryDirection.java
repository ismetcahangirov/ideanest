package az.ideanest.ledger.application;

/**
 * Which way an entry goes.
 *
 * <p>Two values, and the reason they are an enum rather than a sign on the amount is
 * V41's: "which way did this go" is asked in every report over the table, and
 * {@code WHERE signed_amount > 0} reads as a guess at the convention where
 * {@code WHERE direction = 'DEBIT'} does not. The signed amount exists too — it is a
 * generated column, and the balance invariant is a sum over it — but it is derived
 * from this rather than the other way round.
 */
public enum EntryDirection {

    /**
     * Value arriving where the entry points. Money into escrow, an obligation
     * discharged.
     */
    DEBIT,

    /** Value leaving, or an obligation created. What the creator is owed, what the fee is. */
    CREDIT;

    /** The other one. Used by a reversing posting, which is how a correction is made. */
    public EntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
