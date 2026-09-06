package az.ideanest.payout.application;

import az.ideanest.shared.compliance.VerificationStanding;
import java.util.UUID;

/**
 * A payout that will not move because the platform has not verified who the creator is —
 * issue #431.
 *
 * <p>Its own exception rather than a {@code PayoutNotApprovableException} with a different
 * message, because the two are answered differently. "This payout is already paid" is the
 * operator's mistake and the screen reloads; this is nobody's mistake, the payout is correct,
 * and what has to happen next is a creator sending a document to a queue. A console that
 * could not tell them apart would draw the second as the first — V55's argument, which #431
 * quotes: it "makes the same campaign look as though nothing is owed".
 *
 * <p>Carries the standing rather than a sentence, so the console can draw the difference
 * between a creator who has never been asked, one whose documents are in the queue, and one
 * whose approval aged out last week. Those are three different things for a finance operator
 * to do.
 */
public class CreatorNotVerifiedException extends RuntimeException {

    private final UUID payoutId;
    private final UUID creatorId;
    private final transient VerificationStanding standing;

    public CreatorNotVerifiedException(UUID payoutId, UUID creatorId, VerificationStanding standing) {
        super("Payout " + payoutId + " is held: creator " + creatorId + " stands at " + standing);
        this.payoutId = payoutId;
        this.creatorId = creatorId;
        this.standing = standing;
    }

    public UUID payoutId() {
        return payoutId;
    }

    public UUID creatorId() {
        return creatorId;
    }

    public VerificationStanding standing() {
        return standing;
    }
}
