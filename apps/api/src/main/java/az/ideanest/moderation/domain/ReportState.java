package az.ideanest.moderation.domain;

import java.util.List;

/**
 * Where a report is: waiting, or decided one of two ways.
 *
 * <p><strong>Three states, and deliberately no fourth.</strong> The obvious
 * candidate is "under review", claimed by whoever is looking at it, and it is worth
 * having exactly when more than one moderator works the queue at once — which is not
 * yet true, and until it is, it is a flag that gets set by somebody who then closes
 * the tab and never cleared by anybody. A queue where half the rows say a person is
 * working on them and none of them is, is worse than a queue that says nothing.
 *
 * <p><strong>Both resolutions are terminal.</strong> A decided report is not
 * re-opened by editing it: the reporter makes a new report, which V23's partial
 * unique index deliberately permits, and the new row carries the new facts and its
 * own date. Re-opening in place would mean one row with two decisions in it and only
 * the second one visible, on the table an investigation reads.
 */
public enum ReportState {

    /** Nobody has decided yet. Every report starts here, and V23's default says so. */
    OPEN,

    /** A moderator agreed with the complaint. Terminal. */
    UPHELD,

    /** A moderator did not. Terminal. */
    DISMISSED;

    /** The two outcomes a moderator may choose. */
    public static final List<ReportState> RESOLUTIONS = List.of(UPHELD, DISMISSED);

    /** Whether this state is one a moderator can move a report to. */
    public boolean isResolution() {
        return this != OPEN;
    }

    /**
     * Whether a report in this state may be moved to {@code target}.
     *
     * <p>The whole state machine, in one method, so that "which moves are legal" is
     * a question with one answer rather than a condition repeated at each call site
     * — the argument {@code ProjectStateMachine} makes for the campaign's sixteen
     * states, at a scale where it is still worth making: the second copy of this
     * rule is the one that forgets a report can only be decided once.
     */
    public boolean canMoveTo(ReportState target) {
        return this == OPEN && target.isResolution();
    }

    /**
     * What a report in this state can still become. Empty once it has been decided,
     * which is what tells a client to stop offering the buttons rather than to
     * retry.
     */
    public List<ReportState> allowedNext() {
        return this == OPEN ? RESOLUTIONS : List.of();
    }
}
