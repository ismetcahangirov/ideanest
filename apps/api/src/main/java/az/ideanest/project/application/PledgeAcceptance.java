package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
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
 * <p><strong>Live, and before the deadline. Nothing else, yet.</strong> §6.1 has one
 * other state in which money is taken — {@code LATE_PLEDGE}, which is §4.5's PL-16 —
 * and it belongs to epic #72 along with the {@code late_pledge_enabled} and
 * {@code late_pledge_ends_at} columns that govern it and the {@code is_late_pledge}
 * flag a pledge taken then has to carry. Accepting it here would be half of that
 * feature: a late pledge recorded as an ordinary one, in a campaign report that
 * needs the two totals apart. So the narrow rule is stated, and the state that is
 * missing is named rather than quietly included.
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
     * @throws ProjectNotFoundException when there is no such campaign, and when there
     *     is one that has never launched — deliberately the same answer
     * @throws ProjectNotAcceptingPledgesException when a campaign that did launch is
     *     no longer taking pledges — §10.4's {@code PROJECT_NOT_LIVE}
     */
    @Transactional(readOnly = true)
    public void requireAcceptingPledges(UUID projectId) {
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
            throw new ProjectNotAcceptingPledgesException(
                    projectId, project.getState().name(), project.getDeadline());
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
    }
}
