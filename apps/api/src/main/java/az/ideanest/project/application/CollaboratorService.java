package az.ideanest.project.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.project.ProjectProperties;
import az.ideanest.project.application.ProjectAccess.ManagedCollaborator;
import az.ideanest.project.application.ProjectEvents.CollaboratorInvited;
import az.ideanest.project.domain.Capability;
import az.ideanest.project.domain.Collaborator;
import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.CollaboratorRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.SecureTokens;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inviting people onto a campaign, changing what they may do, and taking it back.
 *
 * <p><strong>Nothing here decides authorisation.</strong> Every method starts by
 * asking {@link ProjectAccess} for the campaign, and the two rules that bound
 * delegation — nobody grants more than they hold, and only the creator grants
 * {@link Capability#MANAGE_COLLABORATORS} — live there as well. What lives here is
 * what an invitation <em>is</em>: a token nobody else can reproduce, an expiry, an
 * address that may not have an account behind it yet, and a single acceptance.
 *
 * <p><strong>An invitation to an address with no account is normal.</strong> A
 * creator invites the colleague whose address they know; asking them for an account
 * identifier would mean asking that colleague to register first and then send it
 * back. So the row records the address, and the account is attached at
 * acceptance — by which time the person has both registered and proved they hold
 * the mailbox the link went to.
 *
 * <p><strong>Auditing.</strong> Issuing, changing, and withdrawing a grant are
 * privileged actions, and CLAUDE.md §3 requires every one of them to be audited.
 * Each writes an {@code audit_logs} row (#107) in the same transaction as the
 * change itself, keyed on the grant rather than on the campaign — the grant is what
 * is later widened and withdrawn, and three rows about one collaborator are only a
 * history if they share an identifier. The log lines below stay: they are how an
 * operator watching a deployment sees the same fact without querying, and they are
 * no longer the record. {@code project_state_transitions} could never have held
 * these rows in any case — its check constraints accept the sixteen project states
 * and nothing else, and a grant change is not a state change. A state change made
 * <em>by</em> a collaborator is recorded there, with
 * {@code actor_role = COLLABORATOR}.
 *
 * <p><strong>The invited address is not copied into the audit row.</strong> It is
 * on the {@code collaborators} row this one points at, it is somebody's personal
 * data, and {@code audit_logs} is the one table with no way to remove a row
 * afterwards. What the detail carries is the capabilities, by name — "was given
 * VIEW_FINANCES" is the fact somebody needs later, and "was given three
 * capabilities" is not.
 */
@Service
public class CollaboratorService {

    private static final Logger log = LoggerFactory.getLogger(CollaboratorService.class);

    private final ProjectAccess access;
    private final CollaboratorRepository collaborators;
    private final UserAccounts users;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final ProjectProperties properties;
    private final Clock clock;

    public CollaboratorService(
            ProjectAccess access,
            CollaboratorRepository collaborators,
            UserAccounts users,
            ApplicationEventPublisher events,
            AuditLog audit,
            ProjectProperties properties,
            Clock clock) {
        this.access = access;
        this.collaborators = collaborators;
        this.users = users;
        this.events = events;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Everybody on a campaign: active grants, invitations nobody has accepted, and
     * the grants that were withdrawn.
     *
     * <p>Requires {@link Capability#MANAGE_COLLABORATORS}, which the creator holds
     * implicitly. A collaborator who cannot manage the team is not shown the team:
     * the list contains the addresses of people who have not accepted yet, and an
     * address is somebody's personal data rather than a detail of the campaign. The
     * public "team display" of §4.6 is a different projection and is not this
     * endpoint.
     */
    @Transactional(readOnly = true)
    public List<Collaborator> list(UUID projectId, UUID accountId) {
        access.requireEditable(projectId, accountId, Capability.MANAGE_COLLABORATORS);
        return collaborators.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    /**
     * Issues an invitation.
     *
     * <p>The token exists in this method, in the event it publishes, and in the
     * message that event produces. What is stored is its SHA-256, so the row cannot
     * be turned back into a working link by anybody who can read the table.
     */
    @Transactional
    public Collaborator invite(
            UUID projectId, UUID inviterId, EmailAddress email, Set<Capability> capabilities) {

        Project project = access.requireEditable(projectId, inviterId, Capability.MANAGE_COLLABORATORS);
        requireCapabilities(capabilities);
        access.requireGrantable(project, inviterId, capabilities);
        requireNotTheCreator(project, email);

        collaborators.findLiveByEmail(projectId, email).ifPresent(existing -> {
            throw new CollaboratorAlreadyInvitedException(
                    existing.isActive()
                            ? "That address already works on this campaign. Change their capabilities instead."
                            : "That address already has an invitation to this campaign that has not been used.");
        });

        // Truncated to what the column can hold, for the reason the transition
        // service gives: PostgreSQL stores microseconds, and an expiry computed
        // from a nanosecond-precision instant comes back as a different one.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String token = SecureTokens.generate();

        Collaborator invitation = collaborators.save(Collaborator.invite(
                projectId,
                email,
                SecureTokens.hash(token),
                inviterId,
                capabilities,
                now,
                now.plus(properties.collaborators().invitationTtl())));

        // In this transaction, unlike the event below. A grant that a rollback
        // removed must leave no record saying it was issued.
        audit.record(
                AuditAction.COLLABORATOR_INVITED,
                invitation.getId(),
                AuditActor.user(inviterId),
                AuditOutcome.SUCCEEDED,
                "on project " + projectId + " with " + capabilities);

        // After commit. A link to a grant that a rollback removed cannot be
        // unsent, and the recipient would be clicking something that was never
        // valid.
        events.publishEvent(new CollaboratorInvited(email, token, projectId, inviterId));

        // Privileged, and audited only in the log until #107. Named capabilities
        // rather than a count: "was given VIEW_FINANCES" is the fact somebody
        // needs afterwards, and "was given three capabilities" is not.
        log.info(
                "Collaborator invitation {} issued on project {} by {} for {} with {}.",
                invitation.getId(),
                projectId,
                inviterId,
                email,
                capabilities);
        return invitation;
    }

    /**
     * Replaces a collaborator's capabilities.
     *
     * <p>Replaces rather than merges, because the People tab sends the checkboxes
     * as they now stand and a merge would make unchecking one impossible. The
     * granter's own authority bounds the new set exactly as it bounded the
     * original — otherwise "grant no more than you hold" would be a rule about
     * invitations rather than about grants, and the way around it would be to
     * invite somebody narrowly and widen them a second later.
     */
    @Transactional
    public Collaborator changeCapabilities(UUID collaboratorId, UUID accountId, Set<Capability> capabilities) {
        ManagedCollaborator managed = access.requireManageable(collaboratorId, accountId);
        Collaborator collaborator = managed.collaborator();

        requireCapabilities(capabilities);
        access.requireGrantable(managed.project(), accountId, capabilities);

        if (collaborator.isRevoked()) {
            throw new InvitationRejectedException(
                    "That grant was withdrawn. Invite the person again to give them access.");
        }

        Set<Capability> before = Set.copyOf(collaborator.getCapabilities());
        collaborator.changeCapabilities(capabilities);

        // Both sets, because the interesting question is what somebody gained. A row
        // saying only what they hold now cannot be told apart from the invitation.
        audit.record(
                AuditAction.COLLABORATOR_CAPABILITIES_CHANGED,
                collaboratorId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "from " + before + " to " + capabilities);

        log.info(
                "Collaborator {} on project {} changed by {} from {} to {}.",
                collaboratorId,
                collaborator.getProjectId(),
                accountId,
                before,
                capabilities);
        return collaborator;
    }

    /**
     * Withdraws a grant, or an invitation nobody has accepted.
     *
     * <p>Not a delete: the row is the record that somebody had access to an
     * unlaunched campaign between two dates, which is exactly the question asked
     * after a leak. Revoking something already revoked is not an error — the client
     * asked for a state the row is already in, and {@code DELETE} answering 409 for
     * that would make a retried request look like a failure.
     */
    @Transactional
    public void revoke(UUID collaboratorId, UUID accountId) {
        ManagedCollaborator managed = access.requireManageable(collaboratorId, accountId);
        Collaborator collaborator = managed.collaborator();

        if (collaborator.isRevoked()) {
            return;
        }

        collaborator.revoke(accountId, clock.instant().truncatedTo(ChronoUnit.MICROS));

        // Only when something was withdrawn. The early return above means a repeated
        // DELETE records nothing, which is right: the second request changed no
        // authority, and a row per retry would make a client's timeout look like a
        // second decision.
        audit.record(
                AuditAction.COLLABORATOR_REVOKED,
                collaboratorId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "withdrew " + collaborator.getCapabilities());

        log.info(
                "Collaborator {} on project {} revoked by {}. Capabilities were {}.",
                collaboratorId,
                collaborator.getProjectId(),
                accountId,
                collaborator.getCapabilities());
    }

    /**
     * Claims an invitation for the account presenting it.
     *
     * <p>Single use, unexpired, unrevoked, and addressed to this account. The last
     * of those is what makes an invitation to an unregistered address safe: the link
     * alone is not enough, because the caller also has to be signed in as the
     * address it was sent to — so a forwarded message grants nothing, and the
     * person who registers that address afterwards is the person the creator meant.
     */
    @Transactional
    public Collaborator accept(String rawToken, UUID accountId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Collaborator invitation = collaborators
                .findByInvitationTokenHash(SecureTokens.hash(rawToken))
                // Deliberately the same message as every other failure below that a
                // caller could produce by guessing: an unknown token and a token
                // belonging to a campaign they cannot see are one answer.
                .orElseThrow(() -> new InvitationRejectedException("This invitation is not valid."));

        if (invitation.isRevoked()) {
            throw new InvitationRejectedException("This invitation was withdrawn.");
        }
        if (invitation.getAcceptedAt() != null) {
            throw new InvitationRejectedException("This invitation has already been used.");
        }
        if (invitation.isExpired(now)) {
            throw new InvitationRejectedException("This invitation has expired. Ask for a new one.");
        }

        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new InvitationRejectedException("This invitation is not valid."));
        if (!account.email().equals(invitation.getInvitedEmail())) {
            // The invitation belongs to an address, not to whoever holds the link.
            // Without this a forwarded message would put the wrong person on the
            // campaign, and the creator would have no way to tell.
            throw new InvitationRejectedException("This invitation was sent to a different address.");
        }

        // Claimed with a conditional update rather than by reading and then
        // writing. Two clicks arriving together would both see an unaccepted
        // invitation and both proceed; the database decides which one wins, and it
        // can only decide if the condition is part of the statement.
        if (collaborators.claim(invitation.getId(), accountId, now) == 0) {
            throw new InvitationRejectedException("This invitation has already been used.");
        }

        log.info(
                "Collaborator invitation {} on project {} accepted by {} with {}.",
                invitation.getId(),
                invitation.getProjectId(),
                accountId,
                invitation.getCapabilities());

        // Re-read rather than mutating the instance loaded above: the claim was a
        // bulk update, so the row and that instance no longer agree, and returning
        // the stale one would report a pending invitation to somebody who has just
        // accepted it.
        return collaborators
                .findById(invitation.getId())
                .orElseThrow(() -> new InvitationRejectedException("This invitation is not valid."));
    }

    /**
     * The creator is not a collaborator, and cannot be made one.
     *
     * <p>Their authority is implicit and is not a row. A row for the creator could
     * be revoked or narrowed, which would be a way to lock somebody out of their own
     * campaign — and it would also be the only row whose capabilities were fiction,
     * because the implicit check would keep authorising them regardless.
     */
    private void requireNotTheCreator(Project project, EmailAddress email) {
        Optional<UserAccount> account = users.findByEmail(email);
        if (account.isPresent() && project.isCreatedBy(account.get().id())) {
            throw new ProjectFieldRejectedException(
                    "email", "The campaign's creator already has every capability, and is not a collaborator.");
        }
    }

    /**
     * A grant that confers nothing is not a grant.
     *
     * <p>Refused here rather than by the database, which cannot see the absence of
     * capability rows without a trigger — see the note in
     * {@code V9__create_collaborators.sql}. It is the one rule about this table that
     * the database does not also hold, so it is checked on the one path that creates
     * or changes a grant.
     */
    private static void requireCapabilities(Set<Capability> capabilities) {
        if (capabilities.isEmpty()) {
            throw new ProjectFieldRejectedException(
                    "capabilities", "A collaborator needs at least one capability.");
        }
    }
}
