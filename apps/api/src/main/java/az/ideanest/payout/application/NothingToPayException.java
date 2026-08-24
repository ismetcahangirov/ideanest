package az.ideanest.payout.application;

import java.util.UUID;

/**
 * The campaign has nothing left to pay out - #69.
 *
 * <p>409 rather than 404: the campaign exists and the calculation simply comes to nothing
 * or less. Reached for a campaign that collected nothing, and for one whose refunds and
 * fees have consumed everything it did collect.
 *
 * <p>Refused rather than recorded as a zero payout. A row for nothing would sit in the
 * queue waiting for two signatures on an instruction to send no money, and V55's partial
 * unique index would then stop a real payout being calculated behind it.
 */
public class NothingToPayException extends RuntimeException {

    public NothingToPayException(UUID projectId) {
        super("Campaign " + projectId + " has nothing left to pay out");
    }
}
