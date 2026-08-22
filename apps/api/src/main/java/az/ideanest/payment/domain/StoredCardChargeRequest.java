package az.ideanest.payment.domain;

import az.ideanest.shared.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * §9.2's phase two, as an instruction: collect this amount from this stored card,
 * with the backer absent.
 *
 * <p>This is the request the whole platform is arranged around. §9.1 rejected
 * authorisation holds because they expire before a sixty-day campaign closes and
 * rejected charging up front because it makes the platform hold client funds; what
 * is left is a card saved at pledge time and charged at the deadline, and this
 * record is that charge.
 *
 * @param pledgeId which pledge is being collected. Carried so that an adapter can put
 *     something meaningful in the provider's own reference field, and so that a
 *     provider's dashboard can be reconciled against the platform's by somebody who
 *     has only one of the two open
 * @param projectId which campaign. Same reason, and it is also what a provider that
 *     supports §9.3's R-10 split would settle against
 * @param card what to charge. See {@link StoredCard} for why it is a token and a
 *     scheme identifier and nothing else
 * @param amount how much. <strong>{@link Money}, never a {@code BigDecimal} beside a
 *     currency string</strong> — this is the number a card is charged, and
 *     {@code Money} is where the platform's rounding and currency rules live. An
 *     adapter converts to the provider's own representation with
 *     {@link Money#minorUnits()}, which is exact or throws
 * @param statementDescriptor what the backer will see on their statement. §4.5's
 *     confirmation screen tells them the campaign's name will appear, and a charge
 *     sixty days after a pledge is exactly the charge somebody disputes because they
 *     did not recognise it
 * @param attemptNumber which of §9.6's attempts this is, counted from one. On the
 *     request rather than inferred by the adapter, because it is part of what makes
 *     the idempotency key distinct and because a provider that reports retry
 *     behaviour wants to be told
 * @param idempotencyKey §9.3's R-08. <strong>The same key for every retry of the same
 *     attempt</strong> and a different one for the next attempt, so that a request
 *     the platform is unsure about can be repeated without charging twice while a
 *     genuinely new attempt is not mistaken for a repeat. Derived once, by
 *     {@code CollectionRun}, and stored on {@code transactions.idempotency_key} where
 *     the unique index makes it true of the database and not merely of the provider
 */
public record StoredCardChargeRequest(
        UUID pledgeId,
        UUID projectId,
        StoredCard card,
        Money amount,
        String statementDescriptor,
        int attemptNumber,
        String idempotencyKey) {

    public StoredCardChargeRequest {
        Objects.requireNonNull(pledgeId, "A charge is a charge of a pledge");
        Objects.requireNonNull(projectId, "A charge belongs to a campaign");
        Objects.requireNonNull(card, "There is nothing to charge without a card");
        Objects.requireNonNull(amount, "A charge needs an amount");
        if (!amount.isPositive()) {
            // Not a defensive check: a zero-amount collection would be a verification,
            // which is a different call with different scheme semantics, and a negative
            // one would be a refund submitted through the collection path.
            throw new IllegalArgumentException("A collection charges a positive amount, and this one is " + amount);
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("§9.6's attempts are counted from one, and this one is " + attemptNumber);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("§9.3's R-08 requires an idempotency key on every payment mutation");
        }
        statementDescriptor = statementDescriptor == null ? null : statementDescriptor.trim();
    }
}
