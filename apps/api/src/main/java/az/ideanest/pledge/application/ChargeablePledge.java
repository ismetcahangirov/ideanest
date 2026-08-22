package az.ideanest.pledge.application;

import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A pledge the collection run has claimed, as much of it as the payment module is
 * allowed to see.
 *
 * <p><strong>This record is the module boundary.</strong> §16.1's rule — checked by
 * {@code ModuleBoundaryTests} — is that a module reaches another through its
 * application layer, so the payment module never holds a {@code Pledge} entity. What
 * it needs in order to charge a card is on this record and nothing else is: no backer
 * name, no reward tier, no shipping address, and no mutable handle on the row.
 *
 * <p>The pledge is <strong>locked</strong> for the transaction that received one of
 * these — {@code PledgeRepository#claimNextDueForCharge} is {@code FOR UPDATE SKIP
 * LOCKED} — which is what makes it safe for the payment module to spend the next few
 * seconds talking to a provider about it.
 *
 * @param pledgeId which pledge
 * @param projectId which campaign, for the transaction row and the ledger posting
 * @param backerId whose card. Carried so that the notification §9.6 owes them can be
 *     addressed without the payment module reading {@code pledges}
 * @param amount what to collect: the pledge's total, as {@link Money}. The five
 *     component amounts are deliberately absent — a card is charged one number, and a
 *     charge path that could see the parts is a charge path that can charge the wrong
 *     one
 * @param paymentMethodId which saved card, or <strong>null</strong>, which is what it
 *     is on every pledge the platform holds. {@code payment_methods} is #55, blocked on
 *     #60, so nothing has ever written this column. See {@code StoredCards}
 * @param attemptNumber which of §9.6's attempts this call will be: the pledge's count
 *     plus one. Computed here so that the number on the transaction row, the number in
 *     the idempotency key, and the number the notification reports are one number
 * @param windowEndsAt §9.6's seven days for this pledge. The payment module reads it to
 *     decide whether the next slot it would schedule falls outside the window
 */
public record ChargeablePledge(
        UUID pledgeId,
        UUID projectId,
        UUID backerId,
        Money amount,
        UUID paymentMethodId,
        int attemptNumber,
        Instant windowEndsAt) {}
