package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;

/**
 * A state the submission queue does not serve.
 *
 * <p>Answered as 400 rather than as an empty page, which is the whole reason this
 * exists. {@code GET …/submissions?state=LIVE} has an obvious empty answer, and a
 * moderator reading "nothing here" would take it as a fact about the platform — there
 * is nothing to review — instead of as a question the endpoint does not answer. The
 * refusal names the four it does.
 */
public class UnreviewableStateException extends RuntimeException {

    private final transient ProjectState state;

    public UnreviewableStateException(ProjectState state) {
        super("The submission queue does not serve " + state);
        this.state = state;
    }

    public ProjectState state() {
        return state;
    }
}
