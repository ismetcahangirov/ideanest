package az.ideanest.project.application;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.Capability;
import az.ideanest.project.domain.Collaborator;
import az.ideanest.project.domain.Grants;
import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.CollaboratorRepository;
import az.ideanest.project.infrastructure.ProjectRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who may do what to a campaign. The only place that question is answered.
 *
 * <p><strong>Two kinds of holder, and they are not the same kind.</strong> The
 * creator's authority comes from {@code projects.creator_id} and is implicit:
 * there is no {@code collaborators} row for them, because a row could be revoked
 * or edited, and either would be a way to lock somebody out of their own
 * campaign. Everybody else holds a set of {@link Capability} values granted by
 * somebody who held them first. {@link Grants} is that distinction as a value,
 * and it is what makes "only the creator may grant
 * {@link Capability#MANAGE_COLLABORATORS}" expressible at all.
 *
 * <p><strong>Loading and checking are one call.</strong> Every method here
 * returns the campaign, so there is no way to obtain one without having been
 * authorised for it. Had this class only answered {@code boolean canEdit(...)},
 * the load would have lived in the services and a new service method could have
 * loaded a campaign and forgotten to ask.
 *
 * <p><strong>Coarse and fine forms, deliberately both.</strong> The two-argument
 * methods answer "may this account administer this campaign at all" and are what
 * a caller uses when the request is not about one specific capability — reading
 * the editor projection, or an action that belongs to the creator alone. The
 * three-argument forms name the capability the request needs, and a caller that
 * knows which one it needs is expected to say so: a collaborator with
 * {@code EDIT_STORY} is not a collaborator with {@code EDIT_REWARDS}, and a
 * single coarse check would make those two grants the same grant.
 *
 * <p><strong>404 for a stranger, 403 for a collaborator.</strong> A caller with
 * no relationship to the campaign is told it does not exist — see
 * {@link ProjectNotFoundException} for why that is not evasiveness. A caller who
 * holds an active grant and is missing the capability this request needs gets
 * {@link CapabilityNotGrantedException} and a 403: they were invited, they can
 * already see the campaign, and there is nothing left to hide from them. A
 * <em>revoked</em> collaborator is a stranger again, and gets the 404 — the
 * confidentiality that 404 protects is exactly what a revocation withdraws.
 */
@Service
public class ProjectAccess {

    /**
     * Any one of these means "this account works on the campaign".
     *
     * <p>What the coarse {@link #requireEditable(UUID, UUID)} accepts, because
     * that method serves both the editor's read and its autosave, and somebody
     * granted any editing capability needs to be able to open the editor to
     * exercise it. A collaborator granted only {@code VIEW_FINANCES} or
     * {@code RESPOND_TO_COMMENTS} is not an editor and is refused.
     *
     * <p><strong>Known gap.</strong> {@code PATCH /v1/projects/{id}} carries
     * basics, the story, and the risks section in one body, so this coarse check
     * cannot yet tell a story editor writing a title from one writing a story.
     * Separating them is a per-field decision that belongs with the field-locking
     * work of #36 and the story editor of #35, each of which routes its own fields
     * through the capability-taking form below. Until then an editing collaborator
     * can write any editable field, and that is stated in the pull request rather
     * than pretended away.
     */
    private static final Set<Capability> EDITING =
            EnumSet.of(Capability.EDIT_BASICS, Capability.EDIT_REWARDS, Capability.EDIT_STORY);

    /** No capability grants this; the campaign's creator and nobody else. */
    private static final Set<Capability> CREATOR_ONLY = EnumSet.noneOf(Capability.class);

    private final ProjectRepository projects;
    private final CollaboratorRepository collaborators;
    private final ModeratorDirectory moderators;

    public ProjectAccess(
            ProjectRepository projects, CollaboratorRepository collaborators, ModeratorDirectory moderators) {
        this.projects = projects;
        this.collaborators = collaborators;
        this.moderators = moderators;
    }

    /**
     * The campaign, for reading or editing by this account.
     *
     * <p>No lock: this serves the editor's reads and its autosaves, which are one
     * person typing into one form. Two of their own tabs writing different fields
     * is last-write-wins, which is what an autosaving editor means by design.
     *
     * <p>The creator, or a collaborator holding any of {@link #EDITING}.
     */
    public Project requireEditable(UUID projectId, UUID accountId) {
        return require(projects.findById(projectId), projectId, accountId, EDITING);
    }

    /**
     * The campaign, for a request that needs one named capability.
     *
     * <p>The creator — implicitly, without a row — or a collaborator whose active
     * grant includes {@code capability}. Anything else is refused, and which
     * refusal depends on whether the caller is party to the campaign at all.
     */
    public Project requireEditable(UUID projectId, UUID accountId, Capability capability) {
        return require(projects.findById(projectId), projectId, accountId, EnumSet.of(capability));
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
     *
     * <p><strong>The creator alone.</strong> This is the form for the transitions
     * no grant confers: going live starts taking money from the public, and
     * cancelling abandons commitments people have already made with their card
     * details on file. Both stay with the account that owns the campaign, and a
     * caller that means "submit for review" asks for
     * {@link Capability#SUBMIT_FOR_REVIEW} through the overload below instead of
     * inheriting an authority nobody granted.
     */
    public Project requireTransitionable(UUID projectId, UUID accountId) {
        return require(projects.findByIdForUpdate(projectId), projectId, accountId, CREATOR_ONLY);
    }

    /** The campaign, locked, for a state change this capability confers. */
    public Project requireTransitionable(UUID projectId, UUID accountId, Capability capability) {
        return require(projects.findByIdForUpdate(projectId), projectId, accountId, EnumSet.of(capability));
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
     * <p>{@link ActorRole#CREATOR} for the owner and {@link ActorRole#COLLABORATOR}
     * for an account acting under a grant. The transition service asks rather than
     * assuming because the audit trail has to say which of the two a person was:
     * "the creator submitted this campaign" and "somebody the creator trusted with
     * one capability submitted it" are different facts, and a year later the row is
     * the only place either of them still exists.
     */
    public ActorRole roleOf(Project project, UUID accountId) {
        Grants grants = grantsOf(project, accountId);
        if (grants.isCreator()) {
            return ActorRole.CREATOR;
        }
        if (!grants.isEmpty()) {
            return ActorRole.COLLABORATOR;
        }
        // Unreachable through any of the require* methods above, which is the
        // point: reaching it means a caller obtained a campaign some other way.
        throw new ProjectNotFoundException(project.getId());
    }

    /**
     * What this account holds on this campaign: everything as its creator, an
     * active grant's capabilities as a collaborator, nothing otherwise.
     *
     * <p>Public because both the escalation rule and the People tab need the
     * answer, and because a second implementation of "what may this person do
     * here" is the thing this class exists to prevent.
     */
    public Grants grantsOf(Project project, UUID accountId) {
        if (project.isCreatedBy(accountId)) {
            return Grants.ofCreator();
        }
        return collaborators
                .findActiveGrant(project.getId(), accountId)
                .map(Grants::of)
                .orElseGet(Grants::none);
    }

    /**
     * Refuses a grant the granter is not entitled to issue.
     *
     * <p>The rule is {@link Grants#ungrantable(Set)}: nobody may grant more than
     * they hold, and only the creator may grant
     * {@link Capability#MANAGE_COLLABORATORS}. Checked here rather than in the
     * collaborator service so that authorisation stays in one file — the service
     * decides what an invitation <em>is</em>, this decides whether the person
     * issuing it may.
     *
     * @throws CapabilityNotGrantedException naming the capabilities that were
     *     beyond the granter's authority, so the refusal can be shown against the
     *     checkboxes that caused it rather than as "forbidden". Read as "you would
     *     have to hold these to confer them" — which is exact for seven of the
     *     eight, and for {@link Capability#MANAGE_COLLABORATORS} understates the
     *     rule: holding it is not enough, only the creator may confer it
     */
    public void requireGrantable(Project project, UUID granterId, Set<Capability> requested) {
        Grants grants = grantsOf(project, granterId);
        Set<Capability> beyond = grants.ungrantable(requested);
        if (!beyond.isEmpty()) {
            throw new CapabilityNotGrantedException(project.getId(), beyond, grants.capabilities());
        }
    }

    /**
     * The one implementation of "may this account act on this campaign".
     *
     * @param anyOf the capabilities that would authorise this request, any one of
     *     which is enough. Empty means no capability does and the creator alone may
     *     act — which is a stronger statement than "all of them", and the reason
     *     this parameter is a set rather than a single value
     */
    private Project require(Optional<Project> loaded, UUID projectId, UUID accountId, Set<Capability> anyOf) {
        Project project = loaded.orElseThrow(() -> new ProjectNotFoundException(projectId));
        Grants grants = grantsOf(project, accountId);

        if (grants.isEmpty()) {
            // Not party to this campaign — including somebody whose grant was
            // revoked, and somebody whose invitation is still pending. Neither is
            // told the campaign exists.
            throw new ProjectNotFoundException(projectId);
        }
        if (grants.isCreator()) {
            return project;
        }
        if (anyOf.stream().noneMatch(grants::holds)) {
            throw new CapabilityNotGrantedException(projectId, anyOf, grants.capabilities());
        }
        return project;
    }

    /**
     * A collaborator row, for the endpoints that change one.
     *
     * <p>Here rather than in the service for the reason the rest of this class
     * exists: the row identifies its own campaign, so loading it and deciding
     * whether the caller may manage that campaign's team is one question, and
     * splitting it would let a future endpoint load a grant and forget to ask.
     *
     * @return the row, and the campaign it belongs to, once the caller has been
     *     established as somebody who may manage collaborators on it
     */
    public ManagedCollaborator requireManageable(UUID collaboratorId, UUID accountId) {
        Collaborator collaborator = collaborators
                .findById(collaboratorId)
                .orElseThrow(() -> new CollaboratorNotFoundException(collaboratorId));
        Project project =
                requireEditable(collaborator.getProjectId(), accountId, Capability.MANAGE_COLLABORATORS);
        return new ManagedCollaborator(project, collaborator);
    }

    /** A collaborator row together with the campaign the caller was authorised for. */
    public record ManagedCollaborator(Project project, Collaborator collaborator) {
    }
}
