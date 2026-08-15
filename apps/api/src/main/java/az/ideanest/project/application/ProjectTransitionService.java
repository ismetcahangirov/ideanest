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
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>That constraint is the whole design. Split across the seven endpoints that
 * perform transitions, the audit row becomes something each of them remembers to
 * write, and the first one that forgets produces a campaign whose history has a
 * hole in it — discovered months later, by somebody trying to establish who
 * approved a campaign that should not have been approved.
 *
 * <p><strong>Completeness is re-checked on submission, by the class the
 * checklist endpoint uses.</strong> {@code ProjectChecklistService} evaluates
 * §5.3 for both, which is why {@code GET /v1/projects/{id}/checklist} and this
 * service cannot disagree about whether a campaign is ready. A second, partial
 * version of those rules written inline here would be the bug that arrangement
 * exists to prevent — and the endpoint is advice to a client that may be old or
 * may not be ours, so this is where "state transitions are enforced server-side
 * and cannot be bypassed" is actually true.
 *
 * <p><strong>What is not here.</strong> Transitions driven by time rather than by
 * a person — a scheduled launch arriving, a deadline passing — are scheduled work
 * (§8.4) and belong to the epics that own them; they will call these same methods
 * with {@link ActorRole#SYSTEM}, which is why the actor is a parameter and not the
 * current user.
 */
@Service
public class ProjectTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTransitionService.class);

    private final ProjectAccess access;
    private final ProjectStateTransitionRepository transitions;
    private final ProjectChecklistService checklist;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProjectTransitionService(
            ProjectAccess access,
            ProjectStateTransitionRepository transitions,
            ProjectChecklistService checklist,
            ApplicationEventPublisher events,
            Clock clock) {
        this.access = access;
        this.transitions = transitions;
        this.checklist = checklist;
        this.events = events;
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
     *
     * <p><strong>§5.3 is re-checked here.</strong> The same
     * {@code ProjectChecklistService} the checklist endpoint reads, so a client
     * that showed a complete checklist and a service that refuses the submission
     * is not a state this service can reach. What the endpoint gives is advice;
     * this is the rule, and it holds for a client that skipped the endpoint, cached
     * its answer, or is not ours.
     */
    @Transactional
    public Project submit(UUID projectId, UUID accountId) {
        Project project = access.requireTransitionable(projectId, accountId, Capability.SUBMIT_FOR_REVIEW);

        // The edge first, then the contents — the same order as launch, and for the
        // same reason. A rejected campaign is refused for being rejected, not for
        // having no cover image: the state is the thing the creator cannot fix, and
        // reporting the second failure would send them to upload a picture that
        // would change nothing.
        requireEdge(project.getState(), ProjectState.SUBMITTED);
        checklist.requireSubmittable(project);

        return apply(project, ProjectState.SUBMITTED, access.roleOf(project, accountId), accountId, null);
    }

    /**
     * Opens the campaign's pre-launch page: {@code DRAFT} → {@code PRELAUNCH}.
     *
     * <p><strong>Not in §10.2's endpoint list</strong>, and required by #39's
     * definition of done — the same gap {@link #requestChanges} has, recorded the
     * same way. §6.1 has had the edge since the state machine was written and
     * nothing performed it, so a creator could reach {@code PRELAUNCH} only by a
     * hand-written {@code UPDATE}. §4.6 lists a pre-launch page under Basics and
     * a pre-launch link under Promotion; neither is reachable without this.
     *
     * <p><strong>The creator alone</strong>, through the two-argument
     * {@code requireTransitionable}, which is the form {@link #launch} and
     * {@link #cancel} use. That is a deliberate choice against giving it a
     * capability: opening the page publishes the campaign's title, summary, and
     * cover image to anybody with the link, and §6.1 has no edge back — there is no
     * {@code PRELAUNCH → DRAFT}. A collaborator invited to edit the basics has not
     * been given the authority to make them public, and the difference between
     * those two is exactly what #38's granularity is for.
     *
     * <p>No precondition beyond the edge. A pre-launch page shows the title, the
     * summary, and the cover image, all of which are nullable on a draft; a page
     * with only a title is a thin page, and refusing to open it would be this
     * service deciding what a creator's announcement has to contain. The
     * completeness rules belong to the checklist (#37) and apply to submission.
     */
    @Transactional
    public Project openPrelaunch(UUID projectId, UUID accountId) {
        Project project = access.requireTransitionable(projectId, accountId);
        return apply(project, ProjectState.PRELAUNCH, access.roleOf(project, accountId), accountId, null);
    }

    /**
     * Takes an approved campaign live: {@code APPROVED} or {@code SCHEDULED} →
     * {@code LIVE}.
     *
     * <p>This is the transition money depends on, so it is the one with a
     * precondition beyond the edge: §5.1 resolves a campaign by comparing its
     * total against its goal at its deadline, and neither exists on a campaign
     * that was approved without them.
     *
     * <p>It is also the one transition anybody outside this service is told
     * about. Everybody holding a launch reminder (§4.9 C-11) is owed the message
     * they asked for, and the event below is what starts that — after the commit,
     * because a follower cannot be un-told. See
     * {@link ProjectEvents.ProjectLaunched} for why the event is a latency
     * improvement rather than the delivery guarantee.
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

        Project launched = apply(project, ProjectState.LIVE, access.roleOf(project, accountId), accountId, null);
        events.publishEvent(new ProjectEvents.ProjectLaunched(launched.getId()));
        return launched;
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
