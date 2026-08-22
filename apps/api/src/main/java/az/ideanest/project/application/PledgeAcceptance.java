package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.infrastructure.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a campaign will take a pledge at all.
 *
 * <p><strong>Here rather than in the pledge module, and that is not filing.</strong>
 * The answer is a statement about {@code projects} — a state from §6.1 and a
 * deadline — and {@code ModuleBoundaryTests} keeps this module's entity and its
 * repository to this module. So the question is asked through the application layer,
 * which is the only part of this module the pledge module is entitled to rely on,
 * and the pledge module never learns what a {@link Project} is.
 *
 * <p><strong>And not in {@code ReservationService} either</strong>, whose javadoc
 * says so and says why: reservation is about stock, checking the campaign there
 * would mean this module being depended on in order to say no twice, and the
 * endpoint has to have asked before it reserves anything anyway.
 *
 * <p><strong>Deliberately not {@link ProjectAccess}.</strong> That class answers
 * "may this account act on this campaign", and every one of its methods is about a
 * relationship between a person and a campaign. This is a question about the
 * campaign alone: a backer has no relationship to it, and a rule stated in terms of
 * capabilities would be a rule about the wrong thing.
 *
 * <p><strong>Two windows, and the answer says which.</strong> A campaign takes pledges
 * while it is {@code LIVE} and before its deadline, and again — if its creator opened
 * one — while it is {@code LATE_PLEDGE} and inside the window V6's two columns hold.
 * §4.5's PL-16 and §4.8's PM-23, built by #81.
 *
 * <p>The <em>which</em> is the point of returning something rather than nothing. A
 * late pledge has to be recorded as one: {@code pledges.is_late_pledge} is what keeps
 * the two totals apart in every report that compares what a campaign raised against
 * the goal it was judged on, and a caller that had to ask a second question to find
 * out would be a caller that sometimes forgets to.
 */
@Service
public class PledgeAcceptance {

    private final ProjectRepository projects;
    private final Clock clock;

    public PledgeAcceptance(ProjectRepository projects, Clock clock) {
        this.projects = projects;
        this.clock = clock;
    }

    /**
     * Which window a campaign is taking pledges in.
     *
     * <p>An enum rather than a boolean, because the two are not "normal" and "not
     * normal": they are two funding windows with different rules and different totals,
     * and a caller reading {@code late == false} has to know what the alternative was.
     */
    public enum Window {

        /** The campaign is running: §6.1's {@code LIVE}, before the deadline. */
        FUNDING,

        /**
         * The campaign closed and its creator reopened it: §6.1's {@code LATE_PLEDGE},
         * inside the window. A pledge taken here is stamped {@code is_late_pledge}.
         */
        LATE
    }

    /**
     * Refuses a pledge on a campaign that will not take one.
     *
     * <p>No lock. The campaign's state can change between this check and the pledge
     * being written — a creator can cancel, and the finalizer runs every minute —
     * and a lock held across a checkout would be the wrong answer to that: it would
     * make a backer's draft block a campaign transition, on a row read by every
     * request in the platform. The window is a few milliseconds wide and what is on
     * the other side of it is a DRAFT pledge holding nothing that was charged;
     * cancellation releases those, which is #56's and epic #59's job rather than a
     * reason to serialise the campaign here.
     *
     * @return which of the two windows it is taking them in, which is what decides
     *     whether the pledge is stamped as a late one
     * @throws ProjectNotFoundException when there is no such campaign, and when there
     *     is one that has never launched — deliberately the same answer
     * @throws ProjectNotAcceptingPledgesException when a campaign that did launch is
     *     no longer taking pledges — §10.4's {@code PROJECT_NOT_LIVE}
     */
    @Transactional(readOnly = true)
    public Window requireAcceptingPledges(UUID projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (project.getLaunchedAt() == null) {
            // **A campaign that never launched is answered 404, not 409.**
            // {@link ProjectNotFoundException} explains what a draft is: an
            // unreleased product, a price nobody has been told, sometimes a company
            // that does not exist yet. A refusal that said "this campaign is not
            // live" would confirm to anybody holding an identifier that there is a
            // campaign there, which is precisely what the 404 protects — and it
            // would do it from an endpoint that needs no relationship to the
            // campaign at all.
            //
            // `launched_at` rather than a list of states, because it is the one
            // field that answers "has this ever been public" and cannot fall behind
            // §6.1 gaining a state.
            throw new ProjectNotFoundException(projectId);
        }

        if (!project.isLive()) {
            // §4.5's PL-16. The three facts the campaign has to carry are on the row
            // and are asked as one question, because a campaign in LATE_PLEDGE whose
            // creator has switched the feature off is not taking pledges and neither
            // is one whose window ran out this morning -- and the refusal a backer
            // gets is the same in all three cases, since what they can do about it is
            // the same: nothing.
            if (project.isTakingLatePledges(clock.instant())) {
                return Window.LATE;
            }
            throw new ProjectNotAcceptingPledgesException(
                    projectId,
                    project.getState().name(),
                    // The late-pledge window is the deadline that applies to a campaign
                    // that had one, and reporting the funding deadline instead would
                    // tell a backer their pledge was refused for a date months earlier
                    // than the one they were shown.
                    project.getState() == ProjectState.LATE_PLEDGE
                            ? project.getLatePledgeEndsAt()
                            : project.getDeadline());
        }

        Instant deadline = project.getDeadline();
        // A live campaign whose deadline has passed is one the finalizer has not
        // reached yet (§8.4, every minute). Refusing it here is the difference
        // between a deadline and a suggestion: a pledge taken in that minute would
        // be a commitment made after the funding window the backer was shown.
        //
        // Null cannot happen on a LIVE campaign — projects_public_states_are_fully_specified
        // requires all four of goal, duration, launch, and deadline — and it is
        // treated as acceptable rather than refused, because inventing a refusal for
        // a row the database cannot hold would be a branch nothing can ever test.
        if (deadline != null && !deadline.isAfter(clock.instant())) {
            throw new ProjectNotAcceptingPledgesException(
                    projectId, project.getState().name(), deadline);
        }
        return Window.FUNDING;
    }
}
