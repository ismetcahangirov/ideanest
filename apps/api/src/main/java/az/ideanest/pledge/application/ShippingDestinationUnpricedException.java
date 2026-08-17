package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * Something in the selection is posted, and the creator has not priced posting it to
 * where the pledge is going. §10.4's {@code SHIPPING_DESTINATION_UNPRICED}.
 *
 * <p>A 422 rather than a 400: every field of the request is well formed and the
 * combination is the problem — this tier, to this country. The distinction is what
 * lets a client put the message against the destination selector instead of
 * reporting the whole checkout as malformed.
 *
 * <p><strong>Refused here rather than left to {@link
 * az.ideanest.pledge.domain.PledgeQuote}</strong>, which also refuses it. That class
 * is arithmetic with no HTTP in it, so its refusal is an
 * {@code IllegalArgumentException} with a sentence in it, and §10.4 needs a code the
 * client can branch on and a {@code meta} naming which tier and which country. The
 * check is made where those are known; the quote's own refusal stays as the backstop
 * that makes it impossible to price a pledge nobody costed.
 *
 * <p>A missing destination is the same refusal as an unpriced one, with a null
 * country. "Where is this going" and "we do not ship there" are different sentences
 * for the client to write and the same fact for the platform: this selection cannot
 * be posted as it stands.
 */
public class ShippingDestinationUnpricedException extends RuntimeException {

    private final UUID rewardTierId;
    private final String destinationCountry;

    public ShippingDestinationUnpricedException(UUID rewardTierId, String destinationCountry) {
        super("Reward tier " + rewardTierId + " has no shipping rate for "
                + (destinationCountry == null ? "an unnamed destination" : destinationCountry));
        this.rewardTierId = rewardTierId;
        this.destinationCountry = destinationCountry;
    }

    public UUID rewardTierId() {
        return rewardTierId;
    }

    /** ISO 3166-1 alpha-2, or null when the backer has not said where it goes. */
    public String destinationCountry() {
        return destinationCountry;
    }
}
