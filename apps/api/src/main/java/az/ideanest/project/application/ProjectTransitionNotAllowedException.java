package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.domain.ProjectStateMachine;
import java.util.List;

/**
 * The move the caller asked for is not an edge of §6.1.
 *
 * <p>Answered as 409 with {@code code: PROJECT_TRANSITION_NOT_ALLOWED}. A
 * conflict rather than a bad request: the request was well formed and would have
 * been accepted a moment earlier — what refuses it is the state the campaign is
 * in now, which is frequently the state another tab put it in.
 *
 * <p>Carries what the caller needs to recover: where the campaign actually is,
 * and what it could do from there. A client told only "not allowed" has to reload
 * and guess which of its buttons is still valid.
 */
public class ProjectTransitionNotAllowedException extends RuntimeException {

    private final ProjectState from;
    private final ProjectState to;

    public ProjectTransitionNotAllowedException(ProjectState from, ProjectState to) {
        super("A project in " + from + " cannot move to " + to);
        this.from = from;
        this.to = to;
    }

    public ProjectState from() {
        return from;
    }

    public ProjectState to() {
        return to;
    }

    /** Empty for a terminal state, which is the answer that tells a client to stop offering the action. */
    public List<ProjectState> allowedInstead() {
        return ProjectStateMachine.allowedFrom(from).stream().sorted().toList();
    }
}
