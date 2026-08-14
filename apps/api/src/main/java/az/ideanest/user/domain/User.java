package az.ideanest.user.domain;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person with an account.
 *
 * <p>Holds identity and profile. It deliberately holds no password: see
 * {@code az.ideanest.auth.domain.UserCredential} for why.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Normalised by {@link EmailAddress} and stored in a {@code citext} column,
     * so two addresses differing only in case are one account — and both the
     * application and the unique index agree on that.
     */
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private EmailAddress email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "bio")
    private String bio;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected User() {
        // JPA.
    }

    private User(UUID id, EmailAddress email, String name, String slug, String locale, String currency) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.slug = slug;
        this.locale = locale;
        this.currency = currency;
    }

    /**
     * A new, unverified account. Verification is a separate step and its absence
     * is what {@link #isEmailVerified()} reports.
     */
    public static User register(EmailAddress email, String name, String slug, String locale, String currency) {
        return new User(Identifiers.newIdentifier(), email, name, slug, locale, currency);
    }

    public void markEmailVerified(Instant verifiedAt) {
        // Verifying twice is not an error — a user clicking the link again
        // should see a verified account, not a failure — but the first
        // verification is the one that counts, and later timestamps would
        // rewrite when the account actually became usable.
        if (this.emailVerifiedAt == null) {
            this.emailVerifiedAt = verifiedAt;
        }
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public EmailAddress getEmail() {
        return email;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        // Identity is the identifier, and it is assigned before persistence, so
        // an unsaved and a reloaded instance of the same user compare equal.
        if (this == other) {
            return true;
        }
        return other instanceof User user && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No email. This lands in logs, and §17.4 redacts personal data there.
        return "User[id=" + id + ", slug=" + slug + "]";
    }
}
