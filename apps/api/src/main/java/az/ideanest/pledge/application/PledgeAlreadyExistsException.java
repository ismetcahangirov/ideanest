package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;
import java.util.UUID;

/**
 * This backer already has a live pledge on this campaign. §7.2: one pledge per
 * backer per project, where the pledge is active.
 *
 * <p>A 409, and the identifier of the pledge they already have is the useful part
 * of it — the client's next move is to show that pledge rather than to start a
 * second checkout, which is why this carries it.
 *
 * <p><strong>Including a draft they abandoned a moment ago</strong>, because a
 * draft is a place held on a limited tier and two per backer would let one person
 * exhaust an early-bird tier by reloading the page. What stops that from being a
 * trap is that {@link ReservationService} expires the caller's <em>own</em> lapsed
 * draft before refusing them: a backer whose five minutes ran out is not made to
 * wait for the sweep to notice, and one whose reservation is still live is told
 * they already have it.
 *
 * <p>The state is carried as well, because "you already have a draft" and "you
 * have already backed this campaign" are the same rule and very different
 * sentences.
 *
 * <p><strong>Raised from two places, and deliberately indistinguishable.</strong>
 * {@code ReservationService} raises it from the read that starts a checkout, and
 * again when {@code pledges_project_backer_active_key} refuses an insert that got
 * past that read — which is what two requests from one backer arriving together
 * produce, and is an ordinary double-click rather than an exotic race. The backer
 * gets the same status, the same code, and the same pledge identifier either way;
 * which of the two refused them is our business and not theirs.
 */
public class PledgeAlreadyExistsException extends RuntimeException {

    private final UUID pledgeId;
    private final PledgeState state;

    public PledgeAlreadyExistsException(UUID projectId, UUID backerId, UUID pledgeId, PledgeState state) {
        super("Backer " + backerId + " already has a " + state + " pledge on campaign " + projectId);
        this.pledgeId = pledgeId;
        this.state = state;
    }

    /** The pledge that already exists, so the client can open it instead of starting another. */
    public UUID pledgeId() {
        return pledgeId;
    }

    /** What state it is in, so the client can say which sentence applies. */
    public PledgeState state() {
        return state;
    }
}
