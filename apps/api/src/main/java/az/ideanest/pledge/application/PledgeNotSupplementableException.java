package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;
import java.util.UUID;

/**
 * This pledge cannot buy anything more — §4.8's PM-09 and PM-10.
 *
 * <p>Distinct from {@link PledgeNotEditableException}, which is about §4.5's PL-09 and
 * the states a backer may still change a pledge in. The two sets are different on
 * purpose: a {@code DRAFT} may be edited and may not buy a supplement, because the
 * thing to do with a checkout in progress is finish it; a {@code COLLECTED} pledge may
 * buy one and may not be edited, because the money for it has already moved.
 *
 * <p>Carries the state, so a client can say which of the two it is looking at rather
 * than offering both buttons and letting the server decide.
 */
public class PledgeNotSupplementableException extends RuntimeException {

    private final PledgeState state;

    public PledgeNotSupplementableException(UUID pledgeId, PledgeState state) {
        super("Pledge " + pledgeId + " is in " + state + " and cannot buy more");
        this.state = state;
    }

    /** One of §6.2's twelve. */
    public PledgeState state() {
        return state;
    }
}
