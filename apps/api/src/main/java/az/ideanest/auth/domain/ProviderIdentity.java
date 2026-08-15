package az.ideanest.auth.domain;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person's account at Google or Apple, linked to their account here.
 *
 * <p><strong>The link is the provider's subject, not the address.</strong> Both
 * providers let a person change the email on their account, and Apple's relay
 * address can be switched off entirely. An identity matched on the address means
 * whoever holds that address next inherits the account it used to point at, and
 * every step of that takeover is performed with legitimate credentials. The
 * {@code sub} claim is issuer-scoped, immutable, and never reassigned, so it is
 * the account; the address is a fact recorded about it.
 *
 * <p>Holds {@code userId} as a value rather than an association to {@code User},
 * for the reason given on {@link Session}: the two live in different modules and
 * the foreign key belongs to the database.
 */
@Entity
@Table(name = "provider_identities")
public class ProviderIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private IdentityProvider provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    /**
     * What the provider said the address was, most recently. Nothing
     * authenticates against it — it is here for support and for the audit trail,
     * and it is deliberately allowed to drift from {@code users.email}.
     */
    @Column(name = "email", columnDefinition = "citext")
    private EmailAddress email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /** Apple's Hide My Email: an address that forwards until the user revokes it. */
    @Column(name = "is_private_email", nullable = false)
    private boolean privateEmail;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProviderIdentity() {
        // JPA.
    }

    private ProviderIdentity(UUID id, UUID userId, IdentityProvider provider, String subject, Instant linkedAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.linkedAt = linkedAt;
        this.lastAuthenticatedAt = linkedAt;
    }

    public static ProviderIdentity link(UUID userId, IdentityProvider provider, String subject, Instant at) {
        if (subject == null || subject.isBlank()) {
            // A token without a subject identifies nobody. Storing one would
            // create a row that a later sign-in could collide with.
            throw new IllegalArgumentException("A provider identity needs a subject");
        }
        return new ProviderIdentity(Identifiers.newIdentifier(), userId, provider, subject.trim(), at);
    }

    /**
     * Records what the provider asserted this time, and that it was used.
     *
     * <p>The address is updated because the provider is the authority on its own
     * account. It does <em>not</em> touch {@code users.email}: changing the
     * address someone signs in with here, and the address a receipt goes to, on
     * the strength of a change made somewhere else is a change the person never
     * asked us for.
     */
    public void recordAuthentication(EmailAddress assertedEmail, boolean verified, boolean privateAddress, Instant at) {
        this.email = assertedEmail;
        this.emailVerified = verified;
        this.privateEmail = privateAddress;
        this.lastAuthenticatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public EmailAddress getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isPrivateEmail() {
        return privateEmail;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ProviderIdentity identity && Objects.equals(id, identity.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No subject and no address. The subject identifies a person at the
        // provider, and §17.4 keeps both out of logs.
        return "ProviderIdentity[id=" + id + ", provider=" + provider + ", userId=" + userId + "]";
    }
}
