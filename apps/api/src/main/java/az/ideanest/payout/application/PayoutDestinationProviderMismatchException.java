package az.ideanest.payout.application;

import java.util.UUID;

/**
 * The creator's destination was tokenised by a provider this deployment does not send through
 * — part of issue #432.
 *
 * <p>Its own refusal rather than being folded into "not verified", because the creator has
 * done nothing wrong and there is nothing for a reviewer to look at. §9.3 asks for two
 * providers precisely so the platform can move between them, and the day it does, every stored
 * token becomes meaningless to the new one: a token is issued by a provider and readable only
 * by that provider.
 *
 * <p>What has to happen is a re-tokenisation, by the creator, at the new provider. This
 * refusal is what makes that visible instead of sending a Payriff token to Epoint and reading
 * back a decline nobody can account for.
 */
public class PayoutDestinationProviderMismatchException extends RuntimeException {

    private final UUID payoutId;
    private final UUID creatorId;
    private final transient String sendingThrough;

    public PayoutDestinationProviderMismatchException(UUID payoutId, UUID creatorId, String sendingThrough) {
        super("Payout " + payoutId + " sends through " + sendingThrough
                + " and creator " + creatorId + " has no destination issued by it");
        this.payoutId = payoutId;
        this.creatorId = creatorId;
        this.sendingThrough = sendingThrough;
    }

    public UUID payoutId() {
        return payoutId;
    }

    public UUID creatorId() {
        return creatorId;
    }

    /** The provider this deployment is configured to send through. */
    public String sendingThrough() {
        return sendingThrough;
    }
}
