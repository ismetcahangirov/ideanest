/**
 * What a creator is owed, held, approved, and sent — §9 and §4.11 AD-05, issues #69 and
 * #306.
 *
 * <p><strong>The decision is here and the money is not.</strong> This module owns what a
 * creator is owed, whether the hold has run out, and whether enough people have signed.
 * The movement itself — the provider call, the {@code transactions} row and the ledger
 * posting — is {@code payment.application.PayoutGateway}, because {@code transactions} is
 * the payment module's table and because the two change for different reasons: the rules
 * about approval change when the platform's policy does, and the way money is sent
 * changes whenever a provider does.
 *
 * <p>Nothing here names a payment repository. That is {@code ModuleBoundaryTests} doing
 * real work rather than being obeyed for its own sake.
 */
package az.ideanest.payout;
