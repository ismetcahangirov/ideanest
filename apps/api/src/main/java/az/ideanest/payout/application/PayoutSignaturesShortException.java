package az.ideanest.payout.application;

import java.util.UUID;

/**
 * The row says approved and the signatures on file do not reach the bar - issue #398.
 *
 * <p>409, and deliberately not {@link PayoutNotSendableException}: that one is answered
 * with a state, and the state here is {@code APPROVED}, which would put "this payout is
 * APPROVED and cannot be sent" in front of somebody. The two refusals also lead somewhere
 * different - a payout whose figures moved is recalculated, and this one is signed.
 *
 * <p><strong>Reaching it means the state column and {@code payout_approvals} disagree.</strong>
 * {@code PayoutService.withdrawApproval} produced exactly that disagreement until #398, by
 * calling a transition that silently ignored the state it was handed. The fix moves the
 * state correctly; this guard is what makes the money depend on the rows rather than on the
 * summary of them, so that the next way of writing that column wrong is not a way of
 * sending an unapproved payout.
 */
public class PayoutSignaturesShortException extends RuntimeException {

    private final long signatures;

    private final short required;

    public PayoutSignaturesShortException(UUID payoutId, long signatures, short required) {
        super("Payout " + payoutId + " has " + signatures + " of " + required + " signatures");
        this.signatures = signatures;
        this.required = required;
    }

    public long signatures() {
        return signatures;
    }

    public short required() {
        return required;
    }
}
