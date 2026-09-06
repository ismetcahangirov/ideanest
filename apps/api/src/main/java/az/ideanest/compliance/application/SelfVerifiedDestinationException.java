package az.ideanest.compliance.application;

import java.util.UUID;

/**
 * Somebody tried to confirm their own payout destination — #432.
 *
 * <p>{@code SelfGrantedOverrideException}'s argument, on the control that decides where money
 * goes. V66 states the rule this enforces: "A member of staff may also be a creator. Nothing
 * stops that and nothing should [...] What must stop is one of them approving their own
 * identity verification." A reviewer attesting that their own bank account belongs to them is
 * the same act with a larger number attached.
 *
 * <p>There is no database constraint behind this one, unlike {@code compliance_overrides},
 * because the verifier is nullable on the row and a partial constraint over two nullable
 * columns would be a check that reads as though it did more than it does. The refusal is here
 * and it is tested.
 */
public class SelfVerifiedDestinationException extends RuntimeException {

    private final UUID creatorId;

    public SelfVerifiedDestinationException(UUID creatorId) {
        super("Account " + creatorId + " may not verify its own payout destination");
        this.creatorId = creatorId;
    }

    public UUID creatorId() {
        return creatorId;
    }
}
