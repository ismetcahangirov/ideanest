package az.ideanest.payment.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A card the platform may charge later, as far as this service is ever allowed to
 * know it.
 *
 * <p><strong>There is no card number here and there will not be one.</strong>
 * §17.2 targets SAQ A, which means card data never traverses these servers: what
 * §7.2's {@code payment_methods} holds is a provider token, and what an adapter
 * needs to make §9.2's phase-two charge is that token plus the scheme transaction
 * identifier of the customer-initiated authorisation it is chained to. Those two
 * strings are this record, and the display fields a backer sees — brand, last four,
 * expiry — are deliberately absent because a charge does not need them and a type
 * that carried them would be copied into a log by somebody eventually.
 *
 * <p><strong>{@code payment_methods} does not exist yet.</strong> It is #55, which is
 * blocked on #60, so nothing in the platform can construct one of these from a real
 * card today — {@code StoredCards} is the port that will, and its only implementation
 * answers "there is no card on file", which is true rather than stubbed. This type
 * exists now because {@link PaymentProvider#chargeStoredCard} cannot be written
 * without naming what it charges, and #61's whole point is that the interface is
 * settled before any provider is.
 *
 * @param id the {@code payment_methods} row this came from, for the transaction
 *     record and for the support conversation
 * @param provider which provider minted the token. Carried on the card and not taken
 *     from configuration, because a backer who saved a card while the platform was on
 *     one provider cannot be charged through another — a token is meaningless outside
 *     the provider that issued it, and §9.3's "integrate at least two providers"
 *     makes that a live possibility rather than a theoretical one
 * @param token the provider's reference to the card. <strong>Opaque</strong>: nothing
 *     here parses it, and §18.1's redaction rules keep it out of the log stream
 * @param schemeTransactionId §9.3's R-03. The identifier of the original
 *     customer-initiated authorisation, which scheme rules require the later
 *     merchant-initiated charge to reference. An adapter that omits it submits a
 *     charge the issuer is entitled to decline
 */
public record StoredCard(UUID id, ProviderName provider, String token, String schemeTransactionId) {

    public StoredCard {
        Objects.requireNonNull(id, "A stored card is a payment_methods row and needs its identifier");
        Objects.requireNonNull(provider, "A token means nothing without the provider that issued it");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A stored card without a token cannot be charged");
        }
        if (schemeTransactionId == null || schemeTransactionId.isBlank()) {
            // Refused here rather than at the provider, because the provider's refusal
            // arrives as a decline against a backer's card and reads, in every report
            // and to the backer, as their bank having said no.
            throw new IllegalArgumentException(
                    "§9.3's R-03 requires the scheme transaction identifier of the original authorisation");
        }
    }

    /** The card, with the token unreadable. What a log line is allowed to contain. */
    @Override
    public String toString() {
        return "StoredCard[id=" + id + ", provider=" + provider + ", token=<redacted>]";
    }
}
