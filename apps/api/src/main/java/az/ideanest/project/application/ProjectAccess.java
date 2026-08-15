package az.ideanest.project.application;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.ProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who may do what to a campaign. The only place that question is answered.
 *
 * <p><strong>Today the answer is "the creator, and nobody else".</strong> #38
 * introduces collaborators with granular capabilities, and the point of this
 * class is that it is the one file that issue has to change. A single ownership
 * comparison written inline in six controllers is six places to widen, and the
 * one that gets missed is not a compile error — it is an endpoint that keeps
 * refusing a collaborator for reasons nobody can find.
 *
 * <p><strong>Loading and checking are one call.</strong> Every method here
 * returns the campaign, so there is no way to obtain one without having been
 * authorised for it. Had this class only answered {@code boolean canEdit(...)},
 * the load would have lived in the services and a new service method could have
 * loaded a campaign and forgotten to ask.
 *
 * <p>A caller who may not see the campaign is told it does not exist. See
 * {@link ProjectNotFoundException} for why that is not evasiveness.
 */
@Service
public class ProjectAccess {

    private final ProjectRepository projects;
    private final ModeratorDirectory moderators;

    public ProjectAccess(ProjectRepository projects, ModeratorDirectory moderators) {
        this.projects = projects;
        this.moderators = moderators;
    }

    /**
     * The campaign, for reading or editing by this account.
     *
     * <p>No lock: this serves the editor's reads and its autosaves, which are one
     * creator typing into one form. Two of their own tabs writing different fields
     * is last-write-wins, which is what an autosaving editor means by design.
     */
    public Project requireEditable(UUID projectId, UUID accountId) {
        return projects.findById(projectId)
                .filter(project -> mayAdminister(project, accountId))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /**
     * The campaign, locked until the transaction ends, for a state change by this
     * account.
     *
     * <p>Locked because a state change reads the current state, decides whether
     * the edge is allowed, and writes an audit row that claims that edge happened.
     * Two of those interleaving would produce a history that does not describe
     * any sequence of events that occurred. See
     * {@code ProjectRepository#findByIdForUpdate}.
     */
    public Project requireTransitionable(UUID projectId, UUID accountId) {
        return projects.findByIdForUpdate(projectId)
                .filter(project -> mayAdminister(project, accountId))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /**
     * The campaign, locked, for a moderation decision by this account.
     *
     * <p>Staff is a configured list of addresses, and an empty list is nobody —
     * see {@link ModeratorDirectory} for why that is the shape and why it fails
     * closed. It is deployment configuration standing in for a role model, and it
     * is the only thing that keeps "state transitions are enforced server-side and
     * cannot be bypassed" true before epic #100 exists: without it these endpoints
     * require a valid access token and nothing else, which is a creator approving
     * their own campaign.
     *
     * <p><strong>Checked before the campaign is loaded</strong>, so a caller who
     * is not staff learns nothing about which identifiers exist. Epic #100
     * replaces the directory and changes nothing here.
     */
    public Project requireModeratable(UUID projectId, UUID accountId) {
        if (!moderators.isModerator(accountId)) {
            throw new NotAModeratorException(accountId);
        }
        return projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /**
     * In what capacity this account is acting, for the audit row.
     *
     * <p>Only ever {@link ActorRole#CREATOR} today. #38 returns
     * {@link ActorRole#COLLABORATOR} from here for an account acting under a
     * grant, which is why the transition service asks rather than assuming: the
     * audit trail has to say which of the two a person was, and that is the same
     * question this class already answers.
     */
    public ActorRole roleOf(Project project, UUID accountId) {
        if (project.isCreatedBy(accountId)) {
            return ActorRole.CREATOR;
        }
        // Unreachable through any of the require* methods above, which is the
        // point: reaching it means a caller obtained a campaign some other way.
        throw new ProjectNotFoundException(project.getId());
    }

    /**
     * The rule itself, in one expression.
     *
     * <p>#38 widens this and nothing else: a collaborator with the relevant
     * capability, plus the capability check the endpoints will pass in.
     */
    private static boolean mayAdminister(Project project, UUID accountId) {
        return project.isCreatedBy(accountId);
    }
}
