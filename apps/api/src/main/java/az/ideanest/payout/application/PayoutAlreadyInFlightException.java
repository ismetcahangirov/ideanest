package az.ideanest.payout.application;

import java.util.UUID;

/**
 * The campaign already has a payout on its way - #69.
 *
 * <p>409, and the identifier of the existing one travels with it, because the next thing
 * the reader wants is to open it. V55's partial unique index refuses this anyway; checking
 * first turns a constraint violation into a sentence.
 *
 * <p>Two payouts in flight for one campaign is how a creator gets paid twice for the same
 * collections, which is the mistake this whole module is arranged to prevent.
 */
public class PayoutAlreadyInFlightException extends RuntimeException {

    private final transient UUID existingPayoutId;

    public PayoutAlreadyInFlightException(UUID projectId, UUID existingPayoutId) {
        super("Campaign " + projectId + " already has payout " + existingPayoutId + " in flight");
        this.existingPayoutId = existingPayoutId;
    }

    /** The payout that is already there, so the console can link to it. */
    public UUID existingPayoutId() {
        return existingPayoutId;
    }
}
