package az.ideanest.payment.application;

import az.ideanest.payment.domain.TransactionType;
import java.math.BigDecimal;

/**
 * How much of one kind of transaction has settled, in one currency — issue #70.
 *
 * <p>The row {@code PaymentTransactionRepository.settledTotals()} projects into, kept as a
 * plain {@link BigDecimal} because a JPQL constructor expression is what PostgreSQL's sum
 * lands in and {@code Money} is assembled from it one line later. {@code AccountTotal} does
 * the same thing on the ledger's side, and these two are what the reconciliation compares.
 *
 * @param type which kind of movement. {@code CHARGE} is money in; {@code PAYOUT},
 *     {@code REFUND} and {@code CHARGEBACK} are money out
 * @param currency the ISO code the rows were written in
 * @param total the sum of their amounts, always positive — the direction is the type
 */
public record SettledTotal(TransactionType type, String currency, BigDecimal total) {
}
