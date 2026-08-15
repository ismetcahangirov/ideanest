package az.ideanest.project.application;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.Capability;
import az.ideanest.project.domain.Project;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.domain.ProjectStateMachine;
import az.ideanest.project.domain.ProjectStateTransition;
import az.ideanest.project.infrastructure.ProjectStateTransitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a campaign's state changes.
 *
 * <p><strong>Three things happen together or not at all:</strong> the edge is
 * checked against §6.1, the state is written, and a row is appended to
 * {@code project_state_transitions}. One transaction, one method, and no other
 * path — {@link Project} has no state setter, the repository has no update that
 * touches the column, and every endpoint that changes a state calls one of the
 * methods here.
 *
 * <p>That constraint is the whole design. Split across the six endpoints that
 * perform transitions, the audit row becomes something each of them remembers to
 * write, and the first one that forgets produces a campaign whose history has a
 * hole in it — discovered months later, by somebody trying to establish who
 * approved a campaign that should not have been approved.
 *
 * <p><strong>What is not here.</strong> Submission does not yet re-check the
 * completeness rules of §5.3: the checklist is #37, and it is one class used by
 * both the checklist endpoint and this service precisely so that the two cannot
 * disagree. Writing a second, partial version of those rules here would be the
 * bug that arrangement exists to prevent. Transitions driven by time rather than
 * by a person — a scheduled launch arriving, a deadline passing — are scheduled
 * work (§8.4) and belong to the epics that own them; they will call these same
 * methods with {@link ActorRole#SYSTEM}, which is why the actor is a parameter
 * and not the current user.
 */
@Service
public class ProjectTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTransitionService.class);

    private final ProjectAccess access;
    private final ProjectStateTransitionRepository transitions;
    private final Clock clock;

    public ProjectTransitionService(
            ProjectAccess access, ProjectStateTransitionRepository transitions, Clock clock) {
        this.access = access;
        this.transitions = transitions;
        this.clock = clock;
    }

    /**
     * Records a campaign coming into existence.
     *
     * <p>Called by {@link ProjectEditingService#create} inside its transaction,
     * not on its own: the campaign and the first row of its history are one write,
     * and a project whose history starts at its first submission would be a
     * project nobody can tell was created by the account that owns it.
     *
     * <p>Not {@code @Transactional} on purpose. It has no meaning outside the
     * transaction that inserted the campaign, and an annotation here would suggest
     * it could be called on its own.
     */
    public void recordCreation(Project project) {
        transitions.save(ProjectStateTransition.creation(project.getId(), project.getCreatorId()));
    }

    /**
     * Sends a campaign for review: {@code DRAFT} or {@code PRELAUNCH} or
     * {@code CHANGES_REQUESTED} → {@code SUBMITTED}.
     *
     * <p>All three sources are the same edge as far as this method is concerned,
     * which is what the table is for: the difference between a first submission and
     * a resubmission is in the history, not in the code performing it.
     *
     * <p><strong>The one transition a collaborator can be granted.</strong> #38 gave
     * {@link Capability#SUBMIT_FOR_REVIEW} its own capability, so this asks for it by
     * name rather than using the coarse "may administer this campaign" check that
     * {@link #launch} and {@link #cancel} use — those two are irreversible money
     * decisions and stay with the creator. The audit row then says
     * {@link ActorRole#COLLABORATOR} when somebody else submitted, which is exactly
     * the fact {@code roleOf} exists to record.
     */
    @Transactional
    public Project submit(UUID projectId, UUID accountId) {
        Project project = access.requireTransitionable(projectId, accountId, Capability.SUBMIT_FOR_REVIEW);
        // #37: the completeness checklist is re-checked here, and refuses with
        // PROJECT_NOT_SUBMITTABLE. Until it exists a submission is accepted on
        // the creator's word, and moderation is the check.
        return apply(project, ProjectState.SUBMITTED, access.roleOf(project, accountId), accountId, null);
    }

    /**
     * Takes an approved campaign live: {@code APPROVED} or {@code SCHEDULED} →
     * {@code LIVE}.
     *
     * <p>This is the transition money depends on, so it is the one with a
     * precondition beyond the edge: §5.1 resolves a campaign by comparing its
     * total against its goal at its deadline, and neither exists on a campaign
     * that was approved without them.
     */
    @Transactional
    public Project launch(UUID projectId, UUID accountId) {
        Project project = access.requireTransitionable(projectId, accountId);
        // The edge first, then the data. A campaign still in DRAFT is refused for
        // being in DRAFT, not for having no goal: the state is the thing the
        // creator has to fix first, and reporting the second failure would send
        // them to fill in a field that was not the problem.
        requireEdge(project.getState(), ProjectState.LIVE);
        requireLaunchable(project);
        return apply(project, ProjectState.LIVE, access.roleOf(project, accountId), accountId, null);
    }

    /**
     * Stops a live campaign at the creator's request: {@code LIVE} →
     * {@code CANCELED}, which is terminal.
     *
     * @param reason shown to backers, who committed money to something that is not
     *     going to happen. Required for that reason and recorded on the audit row
     */
    @Transactional
    public Project cancel(UUID projectId, UUID accountId, String reason) {
        Project project = access.requireTransitionable(projectId, accountId);
        if (reason == null || reason.isBlank()) {
            throw new ProjectFieldRejectedException(
                    "reason", "Backers are told why a campaign was cancelled, so a reason is required.");
        }
        return apply(project, ProjectState.CANCELED, access.roleOf(project, accountId), accountId, reason.trim());
    }

    /** Moderation clears a campaign: {@code SUBMITTED} → {@code APPROVED}. */
    @Transactional
    public Project approve(UUID projectId, UUID moderatorId, String note) {
        Project project = access.requireModeratable(projectId, moderatorId);
        return apply(project, ProjectState.APPROVED, ActorRole.MODERATOR, moderatorId, note);
    }

    /**
     * Moderation refuses a campaign: {@code SUBMITTED} → {@code REJECTED}, which is
     * terminal.
     *
     * @param note why. Required, because the creator is shown it and a rejection
     *     without a reason produces a support ticket every time
     */
    @Transactional
    public Project reject(UUID projectId, UUID moderatorId, String note) {
        Project project = access.requireModeratable(projectId, moderatorId);
        return apply(project, ProjectState.REJECTED, ActorRole.MODERATOR, moderatorId, requireNote(note));
    }

    /**
     * Moderation sends a campaign back: {@code SUBMITTED} →
     * {@code CHANGES_REQUESTED}.
     *
     * <p>Not in §10.2's endpoint list, and required by this epic's definition of
     * done. A queue whose only outcomes are approve and reject forces a moderator
     * to reject a campaign over a fixable summary, and a rejection is terminal.
     *
     * @param note what to change. Required: it is the entire content of the state
     */
    @Transactional
    public Project requestChanges(UUID projectId, UUID moderatorId, String note) {
        Project project = access.requireModeratable(projectId, moderatorId);
        return apply(
                project, ProjectState.CHANGES_REQUESTED, ActorRole.MODERATOR, moderatorId, requireNote(note));
    }

    /**
     * The one implementation of "change a campaign's state".
     *
     * @param actorId null only for {@link ActorRole#SYSTEM}; the database enforces
     *     that, so a code path that lost the caller fails loudly rather than
     *     writing an anonymous decision
     */
    private Project apply(Project project, ProjectState target, ActorRole role, UUID actorId, String note) {
        ProjectState from = project.getState();
        requireEdge(from, target);

        // Truncated to what the column can hold. PostgreSQL stores microseconds,
        // and a deadline computed from a nanosecond-precision launch would come
        // back from the database as a different instant than the one the response
        // just reported.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        project.applyTransition(target, now);

        // Same transaction as the state change. Not an event, not an after-commit
        // hook: an audit row that can fail independently of the thing it audits is
        // an audit trail with gaps in exactly the circumstances that produce
        // incidents.
        transitions.save(ProjectStateTransition.of(project.getId(), from, target, role, actorId, note));

        log.info("Project {} moved from {} to {} by {} {}.", project.getId(), from, target, role, actorId);
        return project;
    }

    /**
     * The edge check, as a 409 the client can act on.
     *
     * <p>{@link Project#applyTransition} refuses the same move with an
     * {@link IllegalStateException}, deliberately: by the time it is reached this
     * check has already passed, so an exception from there means a caller bypassed
     * the service, which is a bug and not a request to be answered politely.
     */
    private static void requireEdge(ProjectState from, ProjectState to) {
        if (!ProjectStateMachine.isAllowed(from, to)) {
            throw new ProjectTransitionNotAllowedException(from, to);
        }
    }

    /**
     * A moderation outcome the creator has to act on must say what to act on.
     *
     * <p>Enforced here rather than with an annotation on the request record so
     * that approving — where a note is optional commentary — can share the same
     * body type. The reason belongs to the decision, not to the shape of the JSON.
     */
    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new ProjectFieldRejectedException("note", "A moderation decision the creator sees needs a reason.");
        }
        return note.trim();
    }

    private static void requireLaunchable(Project project) {
        List<String> missing = new ArrayList<>();
        if (project.getGoalAmount() == null) {
            missing.add("goal");
        }
        if (project.getDurationDays() == null) {
            missing.add("durationDays");
        }
        if (!missing.isEmpty()) {
            throw new ProjectNotLaunchableException(missing);
        }
    }
}
