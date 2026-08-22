package az.ideanest.payment.application;

import az.ideanest.payment.domain.StoredCard;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a pledge's saved card comes from — the port {@code payment_methods} will fill.
 *
 * <p><strong>There is no card on file for any pledge the platform holds, and this
 * interface is how that is said out loud.</strong> §9.2's phase one — the verification
 * authorisation, 3-D Secure, the token and the scheme transaction identifier — is #55,
 * blocked on #60. {@code pledges.payment_method_id} exists and is null on every row,
 * and {@code payment_methods} is not a table yet.
 *
 * <p>An interface rather than a direct read of a table that does not exist, because
 * the collection run has to be written against something and the alternative shapes
 * are both worse. A {@code null} threaded through the charge path would make "no card"
 * indistinguishable from "we forgot to look"; a fake card would be the stub §9.2
 * refuses. {@link UnavailableStoredCards} answers "there is no card on file", which is
 * <em>true</em> — not stubbed, not degraded, simply the current state of the platform
 * — and #55 replaces it with a bean that reads the table.
 *
 * <p>Every method returning empty is what makes a collection attempt fail with
 * {@code payment_method_missing}. §9.6's schedule then does what it does for any card
 * that cannot be charged: retries across seven days and drops the pledge. That never
 * happens in a deployed environment, because {@code CollectionRun} refuses to collect
 * at all while no provider is configured — see {@code PaymentProviders}.
 */
public interface StoredCards {

    /**
     * The card to charge for this pledge, if there is one.
     *
     * @param pledgeId which pledge
     * @param paymentMethodId what the pledge says to charge, from
     *     {@code pledges.payment_method_id}. May be null, which is what it is today
     * @return the card, or empty when the pledge names none or names one that has gone
     */
    Optional<StoredCard> forPledge(UUID pledgeId, UUID paymentMethodId);
}
