package az.ideanest.compliance.domain;

import az.ideanest.shared.compliance.DestinationStanding;

/**
 * The state of one {@code payout_destinations} row — part of issue #432.
 *
 * <p>Four values, and the set is deliberately smaller than {@link DestinationStanding}'s
 * six. This is the state of a row; the standing is the answer to "may money leave", which
 * folds in #436's override — a fact that lives in a different table with an expiry on it.
 * A {@code WAIVED} value here would be that waiver copied into a column, and the copy is
 * the one that never expires.
 *
 * <p>The same distinction {@code VerificationState} and {@code VerificationStanding} already
 * draw, one question along.
 */
public enum DestinationState {

    /** Recorded by the creator; nobody has confirmed the account is theirs. */
    AWAITING_VERIFICATION,

    /** The holder's name is not the creator's legal name. */
    NAME_MISMATCH,

    /** A reviewer refused it, with a reason from V58's closed set. */
    REJECTED,

    /** Confirmed, by one of {@link DestinationVerificationMethod}'s three. */
    VERIFIED;

    /**
     * This state as the gate sees it, before any override is applied.
     *
     * <p>The fold is here rather than in the service so that the mapping is one expression
     * in one place. {@code CreatorPayoutDestinations} adds the override and the
     * no-row-at-all case, which are the two things a row cannot say about itself.
     */
    public DestinationStanding asStanding() {
        return switch (this) {
            case AWAITING_VERIFICATION -> DestinationStanding.AWAITING_VERIFICATION;
            case NAME_MISMATCH -> DestinationStanding.NAME_MISMATCH;
            case REJECTED -> DestinationStanding.REJECTED;
            case VERIFIED -> DestinationStanding.VERIFIED;
        };
    }
}
