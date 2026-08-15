package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import java.util.List;

/**
 * A reminder was asked for on a campaign that is not collecting them.
 *
 * <p><strong>Two states accept reminders.</strong> {@link ProjectState#PRELAUNCH}
 * and {@link ProjectState#SCHEDULED} are the two in which a campaign is publicly
 * announced and is not yet taking money. {@link ProjectState#LIVE} is refused
 * because a reminder to be told about something that has already happened is a
 * message nobody will ever be sent, and accepting it quietly would be worse than
 * refusing it: the visitor would believe they had been added to something.
 *
 * <p><strong>Raised only for a campaign the caller can already see.</strong> That
 * is the line between this and {@link ProjectNotFoundException}, and it is drawn
 * in {@code PrelaunchService}: a campaign that has not opened a pre-launch page is
 * a 404, exactly as it is on the page itself, because telling a stranger "this
 * identifier is a draft" is telling them what somebody is preparing. What is left
 * for this exception is the case where the visitor was looking at a real
 * pre-launch page a moment ago and the campaign has moved on underneath them —
 * usually because it launched while the page sat open.
 *
 * <p>A 409 rather than a 400: the request was well formed and would have been
 * accepted a moment earlier. §10.4 asks that a refusal carry something the client
 * can act on, and here that is the state — a client that sees {@code LIVE} sends
 * the visitor to back the campaign, and one that sees {@code CANCELED} does not.
 */
public class RemindersClosedException extends RuntimeException {

    /** The two states in which a pre-launch page exists and collects reminders. */
    public static final List<ProjectState> ACCEPTED_IN =
            List.of(ProjectState.PRELAUNCH, ProjectState.SCHEDULED);

    private final transient ProjectState state;

    public RemindersClosedException(ProjectState state) {
        super("A project in " + state + " does not collect launch reminders");
        this.state = state;
    }

    /** Where the campaign actually is, so the client can decide what to offer instead. */
    public ProjectState state() {
        return state;
    }
}
