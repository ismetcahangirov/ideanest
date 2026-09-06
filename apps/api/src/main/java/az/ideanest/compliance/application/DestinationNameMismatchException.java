package az.ideanest.compliance.application;

import java.util.UUID;

/**
 * A reviewer tried to confirm a destination whose account holder is not the creator — #432.
 *
 * <p><strong>The refusal a reviewer is most likely to think is a mistake, and it is the one
 * worth keeping.</strong> #432 requires three names to agree — the creator's legal name
 * (#430), the SIMA certificate subject (#429), and the bank's account holder — and a control
 * that a reviewer may click past when it disagrees is a control that reports rather than
 * enforces.
 *
 * <p>There are two honest ways past it and both leave a trail. The creator corrects whichever
 * of the two names is wrong, and the comparison runs again on save. Or an administrator grants
 * #436's {@code PAYOUT_DESTINATION} override, which is a row with a reason, an expiry and
 * somebody's name on it — and which a compliance reviewer deliberately cannot grant
 * themselves.
 */
public class DestinationNameMismatchException extends RuntimeException {

    private final UUID creatorId;
    private final String holderName;

    public DestinationNameMismatchException(UUID creatorId, String holderName) {
        super("Creator " + creatorId + "'s destination is held by a differently named party");
        this.creatorId = creatorId;
        this.holderName = holderName;
    }

    public UUID creatorId() {
        return creatorId;
    }

    /**
     * The name on the account.
     *
     * <p>Travels to the console because the reviewer is looking at both names and deciding
     * which is wrong; a refusal that withheld the one it refused on would send them to a
     * different screen to find it.
     */
    public String holderName() {
        return holderName;
    }
}
