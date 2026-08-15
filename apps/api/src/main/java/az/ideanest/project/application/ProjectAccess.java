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

    public ProjectAccess(ProjectRepository projects) {
        this.projects = projects;
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
     * The campaign, locked, for a moderation decision.
     *
     * <p><strong>There is no authorisation check here, and that is a known
     * gap.</strong> The service has no role model: nothing in the schema, the
     * access token, or {@code SecurityConfiguration} distinguishes platform staff
     * from a creator, so the strongest statement that can be made today is the one
     * the filter chain already makes — the caller is authenticated and their
     * account is in good standing. Epic #100 owns administrative roles and audit,
     * and when it lands this method is where the check goes: one method, called by
     * all three moderation endpoints, rather than an annotation somebody forgets
     * on the fourth.
     *
     * <p>Ownership is deliberately <em>not</em> checked instead, as a stand-in. A
     * "creator can approve their own campaign" rule would be worse than no rule,
     * because it looks like authorisation while permitting exactly the thing
     * moderation exists to prevent, and it would have to be found and removed
     * later rather than added.
     */
    public Project requireModeratable(UUID projectId) {
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
