package az.ideanest.project.domain;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Somebody the creator invited onto a campaign, and what they may do on it.
 *
 * <p><strong>One row is an invitation and a grant.</strong> Before acceptance it
 * is an address, a capability set, and a hashed token; after acceptance the same
 * row is what authorises an account. They are not two tables because the second
 * would hold a copy of the first's capability set, and the copy is what
 * eventually disagrees with the original.
 *
 * <p>An invitation to an address with <strong>no account here</strong> is legal
 * and normal — a creator invites a colleague by the address they know, not by a
 * row identifier they have never seen. {@link #getAccountId()} stays null until
 * that address is claimed, which is why authorisation looks the account up by
 * identifier and acceptance looks it up by address.
 *
 * <p>Acceptance is single use and spent by stamping {@link #getAcceptedAt()},
 * never by deleting the row — the reason {@code verification_tokens} gives: a
 * second attempt with the same link has to be distinguishable from a token that
 * never existed, because one of those is a person double-clicking and the other
 * is somebody guessing.
 *
 * <p>The account, the inviter, and the project are identifiers rather than
 * associations, for the reason given on {@link Project}: {@code users} belongs to
 * another module, and a {@code @ManyToOne} across that boundary is the coupling
 * {@code ModuleBoundaryTests} exists to prevent.
 */
@Entity
@Table(name = "collaborators")
public class Collaborator {

    /** SHA-256, as {@code collaborators_token_hash_is_sha256} also requires. */
    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null until the invitation is accepted. See the class comment. */
    @Column(name = "account_id")
    private UUID accountId;

    /**
     * The address the invitation was issued to.
     *
     * <p>{@code citext} named explicitly, as {@code users.email} is: without the
     * column definition Hibernate expects a {@code varchar} and refuses to start
     * against this schema, and the case-insensitive comparison is the point —
     * acceptance compares this against the accepting account's address, and
     * Person@Example.com is the same invitation as person@example.com.
     */
    @Column(name = "invited_email", nullable = false, updatable = false, columnDefinition = "citext")
    private EmailAddress invitedEmail;

    @Column(name = "invitation_token_hash", nullable = false, updatable = false)
    private byte[] invitationTokenHash;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    /**
     * The grant set.
     *
     * <p>Eager, because a grant with its capabilities left unloaded is a grant
     * that authorises nothing until somebody remembers to touch the collection —
     * and the two places that read a collaborator are an authorisation check and
     * the People tab, both of which want the set immediately. The cost is a second
     * select per row on a list of a handful; {@code CollaboratorRepository} fetches
     * the collection in one query where that matters.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "collaborator_capabilities",
            joinColumns = @JoinColumn(name = "collaborator_id", nullable = false))
    @Column(name = "capability", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Capability> capabilities = EnumSet.noneOf(Capability.class);

    /**
     * Written by the application from its own clock, not by the column default,
     * so that it and {@link #getExpiresAt()} are two readings of one clock. The
     * database refuses an expiry that precedes creation, and two clocks — the
     * application's and the database server's — can disagree by enough to violate
     * that for no reason anybody could find afterwards.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected Collaborator() {
        // JPA.
    }

    private Collaborator(
            UUID id,
            UUID projectId,
            EmailAddress invitedEmail,
            byte[] invitationTokenHash,
            UUID invitedBy,
            Set<Capability> capabilities,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.projectId = projectId;
        this.invitedEmail = invitedEmail;
        this.invitationTokenHash = invitationTokenHash.clone();
        this.invitedBy = invitedBy;
        this.capabilities = EnumSet.copyOf(capabilities);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * A pending invitation.
     *
     * @param invitationTokenHash the SHA-256 of the token in the link. The token
     *     itself is never passed to this class, so there is no code path on which
     *     it could be stored by accident
     * @param capabilities at least one. A grant that confers nothing is not a
     *     grant, and the collaborator would be told they had been added to a
     *     campaign they cannot touch
     */
    public static Collaborator invite(
            UUID projectId,
            EmailAddress invitedEmail,
            byte[] invitationTokenHash,
            UUID invitedBy,
            Set<Capability> capabilities,
            Instant createdAt,
            Instant expiresAt) {

        if (invitationTokenHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "An invitation token hash must be SHA-256, " + HASH_LENGTH + " bytes, not "
                            + invitationTokenHash.length);
        }
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("An invitation has to grant at least one capability");
        }
        return new Collaborator(
                Identifiers.newIdentifier(),
                projectId,
                invitedEmail,
                invitationTokenHash,
                invitedBy,
                capabilities,
                createdAt,
                expiresAt);
    }

    /**
     * Claims the invitation for an account.
     *
     * <p>Refuses a second time rather than tolerating it. The caller has already
     * spent the token by a conditional update — see
     * {@code CollaboratorRepository#claim} — so reaching this twice means two
     * requests got past that, and quietly accepting the second would leave the
     * row saying it was accepted at a moment nobody accepted it.
     */
    public void accept(UUID accountId, Instant at) {
        if (this.acceptedAt != null) {
            throw new IllegalStateException("Invitation " + id + " was already accepted at " + acceptedAt);
        }
        this.accountId = Objects.requireNonNull(accountId, "An acceptance names the account that accepted");
        this.acceptedAt = at;
    }

    /**
     * Withdraws the grant.
     *
     * <p>Not a delete. The row is the record that somebody had access to an
     * unlaunched campaign between two dates, and that is exactly what is wanted
     * later — after a leak, or when a creator asks who saw a draft.
     */
    public void revoke(UUID revokedBy, Instant at) {
        if (this.revokedAt != null) {
            // Idempotent would be defensible; refusing is more useful. A second
            // revocation means the client believes this grant is still in force,
            // and it is not.
            throw new IllegalStateException("Collaborator " + id + " was already revoked at " + revokedAt);
        }
        this.revokedBy = Objects.requireNonNull(revokedBy, "A revocation names who performed it");
        this.revokedAt = at;
    }

    /**
     * Replaces the grant set.
     *
     * <p>Replaces rather than adds: the People tab sends the checkboxes as they
     * now stand, and a merge would make removing a capability impossible to
     * express. Whether the caller is allowed to confer these is
     * {@code ProjectAccess}'s decision, not this class's.
     */
    public void changeCapabilities(Set<Capability> capabilities) {
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("A grant has to confer at least one capability");
        }
        this.capabilities.clear();
        this.capabilities.addAll(capabilities);
    }

    /** Accepted, and not revoked: the only state in which this authorises anything. */
    public boolean isActive() {
        return acceptedAt != null && revokedAt == null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Unspent, unrevoked, and unexpired: the only state an invitation may be
     * accepted from.
     */
    public boolean isAcceptable(Instant now) {
        return acceptedAt == null && revokedAt == null && !isExpired(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EmailAddress getInvitedEmail() {
        return invitedEmail;
    }

    /** Defensively copied, as {@code VerificationToken} does: an array is mutable. */
    public byte[] getInvitationTokenHash() {
        return invitationTokenHash.clone();
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Set<Capability> getCapabilities() {
        return capabilities;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Collaborator collaborator && Objects.equals(id, collaborator.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // The address masks itself, and the token hash is deliberately absent:
        // this line reaches logs, and neither the invitee nor the campaign they
        // were invited to is public information.
        return "Collaborator[id=" + id + ", project=" + projectId + ", email=" + invitedEmail + ", active="
                + isActive() + "]";
    }
}
