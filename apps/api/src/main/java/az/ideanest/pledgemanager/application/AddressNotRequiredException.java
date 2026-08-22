package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * A pledge with nothing to post.
 *
 * <p>§4.5's PL-02 — support with no reward — or a digital tier. The pledge names no
 * destination, so there is no parcel and no address to take.
 *
 * <p><strong>Refused rather than stored.</strong> The address would be personal data
 * the platform has no reason to hold, which §17.4's minimisation rule is about, and it
 * would appear on a fulfilment list beside parcels that are actually going somewhere.
 *
 * <p>422: the body is a valid address, and what is wrong is that this pledge does not
 * have one.
 */
public class AddressNotRequiredException extends RuntimeException {

    private final UUID pledgeId;

    public AddressNotRequiredException(UUID pledgeId) {
        super("Pledge " + pledgeId + " has nothing to post");
        this.pledgeId = pledgeId;
    }

    public UUID pledgeId() {
        return pledgeId;
    }
}
