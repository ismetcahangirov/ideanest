package az.ideanest.compliance.application;

import java.util.UUID;

/**
 * A verification or a refusal was aimed at a creator who has filed no destination — #432.
 *
 * <p>Its own exception rather than an empty {@code Optional} returned to the controller,
 * because the two callers want different things from the absence: the gate reads it as
 * {@code DestinationStanding.NONE} and holds the payout, and a reviewer pressing "verify" on
 * a row that is not there has hit a stale screen and should be told so.
 */
public class UnknownPayoutDestinationException extends RuntimeException {

    private final UUID creatorId;

    public UnknownPayoutDestinationException(UUID creatorId) {
        super("Creator " + creatorId + " has recorded no payout destination");
        this.creatorId = creatorId;
    }

    public UUID creatorId() {
        return creatorId;
    }
}
