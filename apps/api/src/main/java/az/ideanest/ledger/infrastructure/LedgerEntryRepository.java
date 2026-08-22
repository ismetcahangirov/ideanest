package az.ideanest.ledger.infrastructure;

import az.ideanest.ledger.domain.LedgerEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and one write: append.
 *
 * <p>There is deliberately no delete, no update, and no {@code saveAndFlush} wrapper
 * that could be mistaken for either. V41 refuses both in PostgreSQL; what this
 * interface adds is that a developer looking for the method does not find one.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Every entry of one posting, in the order they were written. */
    List<LedgerEntry> findByTransactionIdOrderByIdAsc(UUID transactionId);

    /**
     * The balance of one account on one campaign, in one currency.
     *
     * <p><strong>A sum over the signed amount, computed by PostgreSQL.</strong> Reading
     * the entries and adding them up in Java would be correct and would also mean
     * loading every collection a campaign ever took in order to answer what a creator
     * is owed — a number a payout screen asks for on every view.
     *
     * <p>Positive means debits exceed credits. For {@link az.ideanest.ledger.application.LedgerAccount#ESCROW}
     * that is money the platform is holding; for a creator's account it is money the
     * platform has already paid out beyond what was earned, which should never be
     * positive and is worth alerting on when it is.
     *
     * <p>{@code COALESCE} rather than a nullable return, because an account with no
     * entries has a balance of nothing rather than no balance — and a null here would
     * be unwrapped into a {@code Money} by the caller, which is where the
     * {@code NullPointerException} would surface instead.
     *
     * @return the net as a plain {@link BigDecimal}; the caller pairs it with the
     *     currency it asked for. See {@code MoneyAmountConverter} for why money crosses
     *     this boundary as two values
     */
    @Query(
            """
            SELECT COALESCE(SUM(CASE WHEN e.direction = az.ideanest.ledger.application.EntryDirection.DEBIT
                                     THEN e.amount ELSE -e.amount END), 0)
            FROM LedgerEntry e
            WHERE e.account = :account AND e.projectId = :projectId AND e.currency = :currency
            """)
    BigDecimal balanceOf(
            @Param("account") String account,
            @Param("projectId") UUID projectId,
            @Param("currency") String currency);

    /**
     * The same, across every campaign: what the account holds in total.
     *
     * <p>The platform-wide read, which is what §8.4's {@code ledger-reconciliation}
     * (#70) compares against a provider's settlement report and what §22.1's regulatory
     * position is argued from. Per currency, because §21.2 has no rate at which two of
     * them add up.
     */
    @Query(
            """
            SELECT COALESCE(SUM(CASE WHEN e.direction = az.ideanest.ledger.application.EntryDirection.DEBIT
                                     THEN e.amount ELSE -e.amount END), 0)
            FROM LedgerEntry e
            WHERE e.account = :account AND e.currency = :currency
            """)
    BigDecimal balanceOf(@Param("account") String account, @Param("currency") String currency);

    /**
     * Whether anything has been posted against this transaction.
     *
     * <p>The guard that makes {@code Ledger#post} refuse a second posting for one
     * transaction. It is not the only guard — {@code transactions.idempotency_key} is
     * unique, so the transaction row itself cannot be written twice — but it is the one
     * that catches a caller reusing a transaction identifier it already used, which the
     * unique key cannot see.
     */
    boolean existsByTransactionId(UUID transactionId);
}
