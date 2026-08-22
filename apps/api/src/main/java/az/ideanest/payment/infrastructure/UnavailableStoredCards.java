package az.ideanest.payment.infrastructure;

import az.ideanest.payment.application.StoredCards;
import az.ideanest.payment.domain.StoredCard;
import java.util.Optional;
import java.util.UUID;

/**
 * There is no card on file, because there is no {@code payment_methods} table.
 *
 * <p><strong>Not a stub.</strong> A stub returns a made-up answer so that code above it
 * can be exercised; this returns the true answer. §9.2's phase one is #55, blocked on
 * #60, so no card has ever been tokenised and {@code pledges.payment_method_id} is null
 * on every row the platform holds. "There is no card on file" is a fact about the
 * platform, and the day #55 lands it stops being one.
 *
 * <p>Registered by {@code PaymentConfiguration} only when nothing else supplies a
 * {@link StoredCards}, so #55 replaces it by existing and a test can supply its own
 * without a bean-definition clash.
 */
public class UnavailableStoredCards implements StoredCards {

    @Override
    public Optional<StoredCard> forPledge(UUID pledgeId, UUID paymentMethodId) {
        return Optional.empty();
    }
}
