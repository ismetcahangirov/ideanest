package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.infrastructure.ProjectRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a campaign may be read by somebody with no relationship to it, for a
 * caller that may not name a campaign.
 *
 * <p><strong>The counterpart of {@link ProjectAccess}, not a variant of it.</strong>
 * That class answers "who may act on this campaign" and every one of its methods
 * takes an account. The public surfaces have no account to take — a visitor reading
 * a campaign page is the audience they exist for — and what stands in for
 * authorisation is the campaign's state, exactly as {@link PrelaunchService} says of
 * its own three methods. Keeping that second rule in one place is the same argument
 * that gave the first one a home: written inline in each public endpoint, it becomes
 * one rule per endpoint, and the first one that forgets {@code SUSPENDED} publishes a
 * campaign trust and safety stopped.
 *
 * <p><strong>The nine states, and why they are these nine.</strong> They are exactly
 * {@code DiscoveryStatus.PUBLIC_STATES}, which is the set every read in the discovery
 * module already applies. Two of the sixteen decide the shape:
 *
 * <ul>
 *   <li>{@link ProjectState#PRELAUNCH} is in. It has a public page — that is what the
 *       {@code DRAFT → PRELAUNCH} edge is for — so a stranger holding its identifier
 *       is looking at something the creator published.
 *   <li>{@link ProjectState#SUSPENDED} is out, although it is a campaign the public
 *       has already seen and pledged to. It was stopped by trust and safety,
 *       frequently while an investigation is open, and continuing to serve its
 *       reward tiers would be offering for sale what the platform has just withdrawn.
 * </ul>
 *
 * <p>The set is written here rather than imported from discovery because that one
 * lives in {@code discovery.domain}, which is another module's internals —
 * {@code ModuleBoundaryTests} forbids reaching into it, and rightly: discovery reads
 * {@code projects.state} as text out of its own SQL and has no {@link ProjectState}
 * to say it with. Two independent statements of one rule, checked against each other
 * in a test, is the arrangement {@code DiscoveryStatus} already uses for its own
 * partition, and it is better than one statement and one derivation that cannot
 * disagree because nobody ever reads it.
 *
 * <p><strong>404, never 403.</strong> A campaign that is not publicly visible is
 * answered as one that does not exist, which is the answer {@link ProjectAccess}
 * gives a stranger and the answer {@link PrelaunchService#page} gives for a draft. A
 * 403 would confirm the identifier is real, and what it would confirm about is a
 * campaign somebody is still preparing or one a moderator has just stopped.
 */
@Service
public class PublicProjects {

    /** See the class comment. The same nine as {@code DiscoveryStatus.PUBLIC_STATES}. */
    private static final Set<ProjectState> VISIBLE = EnumSet.of(
            ProjectState.PRELAUNCH,
            ProjectState.LIVE,
            ProjectState.CANCELED,
            ProjectState.SUCCESSFUL,
            ProjectState.UNSUCCESSFUL,
            ProjectState.COLLECTING,
            ProjectState.LATE_PLEDGE,
            ProjectState.FULFILLING,
            ProjectState.COMPLETED);

    private final ProjectRepository projects;

    public PublicProjects(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * The campaign's public facts, if it has any.
     *
     * <p>Loading and checking are one call, as everywhere else in this module: a
     * method that only answered {@code boolean} would leave the load in the callers,
     * and a future caller would load a campaign and forget to ask.
     *
     * @return what a public endpoint of another module is allowed to know about a
     *     campaign, which is deliberately two values rather than the entity. A
     *     {@link Project} in another module's hands is that module reaching into this
     *     one's domain, and it is what {@link EditLocks} already refuses to do for the
     *     same reader
     * @throws ProjectNotFoundException for an identifier that does not exist and for
     *     one whose state is not public. The same exception for both, on purpose
     */
    @Transactional(readOnly = true)
    public PublicCampaign requireVisible(UUID projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (!VISIBLE.contains(project.getState())) {
            throw new ProjectNotFoundException(projectId);
        }
        return new PublicCampaign(project.getState().name(), project.getCurrency());
    }

    /**
     * A publicly visible campaign, as the modules that hang off it need it.
     *
     * @param state by name, because the module asking has no enum to hold it in —
     *     the same reasoning {@link EditLocks} gives
     * @param currency what every price on the campaign is denominated in. It is on
     *     the campaign rather than on the tier as far as a client is concerned, and a
     *     reward list has to be able to state it even when the campaign has no tiers
     *     at all
     */
    public record PublicCampaign(String state, String currency) {
    }
}
