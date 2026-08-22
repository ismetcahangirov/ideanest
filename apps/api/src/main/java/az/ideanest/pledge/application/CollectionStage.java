package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;

/**
 * Which of §9.6's two queues a collection pass is draining.
 *
 * <p>§8.4 gives them two jobs with two schedules — {@code charge-processor} every
 * minute and {@code charge-retry} every six hours — and this is the parameter that
 * tells them apart. One job doing both would be wrong twice over: the initial
 * collection at a campaign's close wants to happen now, and a retry six hours later
 * wants not to; and {@code JobRunner} counts failures per job name, so a database
 * problem in the retry sweep would back the initial collection off too, on the one
 * pass where lateness is most visible.
 *
 * <p>The two queues never contend, because a pledge is in exactly one state.
 */
public enum CollectionStage {

    /**
     * §9.6's first row: the initial collection, immediately after the campaign closed.
     * Every pledge here has had nothing tried against it — or has had a charge the
     * provider accepted and has not decided, which stays in this queue precisely because
     * it is not a refusal.
     */
    INITIAL(PledgeState.CHARGE_PENDING),

    /** §9.6's rows two to four: a card that was refused, waiting for its next slot. */
    RETRY(PledgeState.CHARGE_FAILED);

    private final PledgeState state;

    CollectionStage(PledgeState state) {
        this.state = state;
    }

    /** The pledge state this queue is made of. */
    public PledgeState state() {
        return state;
    }
}
