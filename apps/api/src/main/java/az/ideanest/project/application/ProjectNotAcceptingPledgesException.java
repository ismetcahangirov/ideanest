package az.ideanest.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The campaign exists and is not taking pledges.
 *
 * <p>§10.4's {@code PROJECT_NOT_LIVE}, and a 409 rather than a 404 or a 403: the
 * caller may see this campaign — it launched, so it is public — and there is nothing
 * wrong with their request except its timing. A campaign whose deadline passed
 * minutes ago and one that was never approved are the same refusal to a backer, and
 * the state is in the problem's {@code meta} so a client can say which.
 *
 * <p><strong>Distinct from {@link ProjectNotFoundException}</strong> on purpose. A
 * draft is confidential and is answered 404; a campaign that is over is a public
 * fact, and pretending it never existed would break every link anybody ever shared
 * to it.
 */
public class ProjectNotAcceptingPledgesException extends RuntimeException {

    private final String state;
    private final Instant deadline;

    public ProjectNotAcceptingPledgesException(UUID projectId, String state, Instant deadline) {
        super("Project " + projectId + " is not accepting pledges in state " + state);
        this.state = state;
        this.deadline = deadline;
    }

    /** One of §6.1's sixteen. Safe to report: the campaign is public. */
    public String state() {
        return state;
    }

    /**
     * When it stopped, or null when it never started.
     *
     * <p>A campaign refused because its deadline passed has one; a campaign refused
     * because it is still in review does not, and the difference is what lets a
     * client say "this ended on Tuesday" rather than "not available".
     */
    public Instant deadline() {
        return deadline;
    }
}
