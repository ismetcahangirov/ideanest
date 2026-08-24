package az.ideanest.ledger.application;

import az.ideanest.shared.money.Money;

/**
 * What one account holds, in one currency.
 *
 * <p>{@link AccountTotal} with the amount and the currency put back together, which is the
 * boundary {@code MoneyAmountConverter} draws: money is two columns in PostgreSQL, one
 * value in Java, and a string on the wire (§10.3).
 *
 * @param account the stored account name — {@code escrow}, {@code platform_fee},
 *     {@code creator:{id}}, and the rest of §7.2's six
 * @param net debits minus credits. Positive on {@link LedgerAccount#ESCROW} is money the
 *     platform is holding; positive on a creator's account is money paid out beyond what was
 *     earned, which should never happen
 */
public record LedgerBalance(String account, Money net) {

    static LedgerBalance of(AccountTotal total) {
        return new LedgerBalance(total.account(), Money.of(total.net(), total.currency()));
    }
}
