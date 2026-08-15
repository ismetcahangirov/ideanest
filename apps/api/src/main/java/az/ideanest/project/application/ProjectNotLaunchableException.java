package az.ideanest.project.application;

import java.util.List;

/**
 * The edge to {@code LIVE} is allowed, and the campaign has nothing to be live
 * with.
 *
 * <p>Answered as 409 with {@code code: PROJECT_NOT_LAUNCHABLE} and the missing
 * fields in {@code meta}. A conflict rather than a bad request, for the same
 * reason as a refused transition: the request is well formed and the state of the
 * campaign is what refuses it.
 *
 * <p><strong>Unreachable through the API, and kept.</strong> §5.1 resolves a
 * campaign by comparing its total against its goal at its deadline, so a live
 * campaign without a goal or a duration cannot be resolved at all —
 * {@code projects_public_states_are_fully_specified} refuses the row outright.
 * {@code ProjectChecklistService} now refuses to submit such a campaign, so it
 * cannot reach {@code APPROVED} to be launched from, and nothing a client can send
 * raises this.
 *
 * <p>It stays because the alternative is trusting that every future path to
 * {@code LIVE} went through a submission. The scheduled launch of §8.4 does not:
 * it moves {@code SCHEDULED → LIVE} on a timer, from a row somebody may have
 * edited in between. Without this check that launch reaches the database and comes
 * back as a 500, which tells the creator nothing and pages somebody. A guard whose
 * value is that it never fires is not one to delete the moment it stops firing.
 */
public class ProjectNotLaunchableException extends RuntimeException {

    private final List<String> missing;

    public ProjectNotLaunchableException(List<String> missing) {
        super("A project cannot go live without " + String.join(" and ", missing));
        this.missing = List.copyOf(missing);
    }

    /** JSON field names, so a client can point at the inputs that are empty. */
    public List<String> missing() {
        return missing;
    }
}
