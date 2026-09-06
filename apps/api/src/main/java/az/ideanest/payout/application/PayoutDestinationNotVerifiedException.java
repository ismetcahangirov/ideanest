package az.ideanest.payout.application;

import az.ideanest.shared.compliance.DestinationStanding;
import java.util.UUID;

/**
 * A payout that will not move because nobody has confirmed where it would go — part of #432.
 *
 * <p>{@code CreatorNotVerifiedException}'s sibling, one question along, and separate from it
 * for the same reason it is separate from {@code PayoutNotApprovableException}: the two are
 * answered differently. "We have not established who this creator is" sends a creator to an
 * identity queue; this sends them to a form where they say which account is theirs, or sends a
 * compliance reviewer to the one they already filled in.
 *
 * <p>Carries the standing rather than a sentence, so the console can tell a creator who has
 * filed nothing from one whose account is waiting on a reviewer from one whose account is held
 * by somebody else. Those are three different things to do about it.
 */
public class PayoutDestinationNotVerifiedException extends RuntimeException {

    private final UUID payoutId;
    private final UUID creatorId;
    private final transient DestinationStanding standing;

    public PayoutDestinationNotVerifiedException(UUID payoutId, UUID creatorId, DestinationStanding standing) {
        super("Payout " + payoutId + " is held: creator " + creatorId + "'s destination stands at " + standing);
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

    public DestinationStanding standing() {
        return standing;
    }
}
