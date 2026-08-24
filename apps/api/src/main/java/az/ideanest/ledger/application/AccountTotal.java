package az.ideanest.ledger.application;

import java.math.BigDecimal;

/**
 * One account's net position in one currency, straight out of the aggregate.
 *
 * <p>The row {@code LedgerEntryRepository.balances()} projects into, kept as a plain
 * {@link BigDecimal} because a JPQL constructor expression is what PostgreSQL's sum lands
 * in and {@code Money} is assembled from it one line later. See
 * {@code shared.money.MoneyAmountConverter} for why an amount and its currency cross a
 * persistence boundary as two values.
 *
 * <p>Signed the way the ledger is signed: debits positive, credits negative. Positive on
 * {@link LedgerAccount#ESCROW} is money the platform is holding; positive on a creator's
 * account is money paid out beyond what was earned, which should never happen and is worth
 * an alert when it does.
 *
 * @param account the stored account name, which is {@link LedgerAccount#name()}
 * @param currency the ISO code the entries were written in
 * @param net debits minus credits, never summed across currencies (§21.2)
 */
public record AccountTotal(String account, String currency, BigDecimal net) {
}
