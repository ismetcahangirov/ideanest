package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;
import java.util.UUID;

/**
 * The pledge is in a state its backer may no longer change or withdraw. §10.4's
 * {@code PLEDGE_NOT_EDITABLE}.
 *
 * <p>§4.5's PL-09 and PL-10 are bounded by two facts, and this is one of them:
 * {@link PledgeState#EDITABLE} carries which states a backer may still act on and
 * why the other ten are out. The second fact is the campaign — "until the deadline"
 * — and it is deliberately <em>not</em> this exception. A campaign that has closed
 * is answered with {@code PROJECT_NOT_LIVE}, the same code and the same body
 * {@code POST /v1/pledges/draft} already gives, so that one fact has one answer
 * across the four endpoints that ask about it and the client keeps the deadline it
 * needs to say "this campaign ended on Tuesday". See {@code PledgeService#edit}.
 *
 * <p><strong>Not idempotency, and not the reservation.</strong> A retried edit
 * carrying the same {@code Idempotency-Key} never reaches here — it is replayed with
 * its original response. A draft whose five minutes ran out is
 * {@link ReservationExpiredException}, because "your reservation lapsed, start
 * again" is a different instruction from "this pledge is over".
 *
 * <p>The state is carried because the client's next move depends on it: an
 * {@code EXPIRED} draft is started again, a {@code COLLECTED} pledge is a
 * conversation with the creator.
 */
public class PledgeNotEditableException extends RuntimeException {

    private final UUID pledgeId;
    private final PledgeState state;

    public PledgeNotEditableException(UUID pledgeId, PledgeState state) {
        super("Pledge " + pledgeId + " is " + state + " and can no longer be changed by its backer");
        this.pledgeId = pledgeId;
        this.state = state;
    }

    public UUID pledgeId() {
        return pledgeId;
    }

    /** One of §6.2's twelve, so the client can say which sentence applies. */
    public PledgeState state() {
        return state;
    }
}
