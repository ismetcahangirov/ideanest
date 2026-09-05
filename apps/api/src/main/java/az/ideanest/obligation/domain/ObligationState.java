package az.ideanest.obligation.domain;

/**
 * How a campaign is doing against §5.5's monthly update — issue #437.
 *
 * <p><strong>Derived and never stored.</strong> There is no state column on
 * {@code update_obligations}: the state is a comparison between {@code due_at} and now, so it
 * changes because time passed rather than because a job ran. A stored state would mean a
 * scheduler failing leaves every campaign reading {@code CURRENT} while none of them is.
 *
 * <p>Four values and not two, because the screen says something different about each and because
 * §22.3 asks for a fact rather than a verdict. {@link #NEVER_UPDATED} is separated from
 * {@link #LAPSED} for the same reason {@code FulfilmentProgress} separates {@code untouched} from
 * {@code preparing}: "has not posted since the campaign closed" and "posted, and not recently"
 * are the same to nobody who is reading the page to decide whether to back this creator again.
 */
public enum ObligationState {

    /** An update is not due yet. The ordinary state, and the one most campaigns are in. */
    CURRENT,

    /** The month is nearly up. What the reminder is sent on, and what the creator's own screen shows. */
    DUE_SOON,

    /** The month is up and nothing has been published since the campaign closed. */
    NEVER_UPDATED,

    /** The month is up, and there was an earlier update. The date of it is the fact worth stating. */
    LAPSED,

    /**
     * Fulfilment is complete. The obligation is over and the row stays.
     *
     * <p>Not the same as {@link #CURRENT}, and a profile that showed them the same way would be
     * hiding the difference between a creator who delivered and one who is still going.
     */
    COMPLETE
}
