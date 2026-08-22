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

    /**
     * What an anonymised account is called. Fixed rather than blank: {@code name}
     * is {@code NOT NULL} and is rendered next to retained records, and "" there
     * reads as a bug rather than as a person who left.
     */
    public static final String ANONYMOUS_NAME = "Deleted account";

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

    /**
     * §4.11's AD-04 (#104): when trust and safety stopped this account.
     *
     * <p>Orthogonal to the deletion lifecycle below it, and V40 argues why: deletion is
     * something a person asks for and §17.4 eventually anonymises, a suspension is
     * something the platform does to an account and keeps every row readable for. An
     * account can be both.
     */
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_by")
    private UUID suspendedBy;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "deletion_scheduled_at")
    private Instant deletionScheduledAt;

    @Column(name = "anonymised_at")
    private Instant anonymisedAt;

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

    /**
     * Starts the grace period.
     *
     * <p>Deliberately does <em>not</em> set {@code deletedAt}. The account stays
     * findable, because the way back is to sign in and cancel, and every finder
     * in this module excludes soft-deleted rows — soft-deleting here would make
     * the recovery the grace period exists for impossible.
     *
     * <p>Asking twice keeps the first date. A client that retries would
     * otherwise push the deletion further away every time it did, and the date
     * the user was told would quietly stop being true.
     */
    public void requestDeletion(Instant requestedAt, Instant scheduledFor) {
        if (this.deletionRequestedAt == null) {
            this.deletionRequestedAt = requestedAt;
            this.deletionScheduledAt = scheduledFor;
        }
    }

    /** Withdraws the request. Nothing has been overwritten yet, so this is a complete recovery. */
    public void cancelDeletion() {
        this.deletionRequestedAt = null;
        this.deletionScheduledAt = null;
    }

    public boolean isDeletionPending() {
        return deletionRequestedAt != null && anonymisedAt == null;
    }

    public boolean isAnonymised() {
        return anonymisedAt != null;
    }

    public boolean isAnonymisationDue(Instant now) {
        return isDeletionPending() && !now.isBefore(deletionScheduledAt);
    }

    /**
     * Overwrites everything that identifies the person, in place.
     *
     * <p>Not a delete. A pledge is a financial record, and "pledge #123 was made
     * by user X" has to stay true after X leaves or the ledger stops
     * reconciling; every one of those rows is a foreign key to this one. So the
     * row survives and its contents do not.
     *
     * <p>The address becomes a {@code .invalid} one — RFC 2606 reserves that
     * suffix and it can never resolve, so no queue, retry, or report can deliver
     * to it by accident. The slug is replaced rather than blanked because it is
     * {@code NOT NULL} and unique, and because a public profile URL that still
     * reads the person's name is not anonymised.
     *
     * <p>Doing this twice is a no-op, which is what makes the job that calls it
     * safe to run again after a crash.
     */
    public void anonymise(Instant at) {
        if (this.anonymisedAt != null) {
            return;
        }
        String marker = id.toString().replace("-", "");
        this.email = EmailAddress.of("deleted-" + marker + "@anonymised.invalid");
        this.name = ANONYMOUS_NAME;
        this.slug = "deleted-" + marker;
        this.avatarUrl = null;
        this.bio = null;
        // Cleared with the address it referred to. Kept, it would say that some
        // address we no longer hold was once proven, which is a fact about a
        // person we have just finished forgetting.
        this.emailVerifiedAt = null;
        // locale and currency stay. Neither identifies anybody, and currency is
        // the unit the retained financial rows are denominated in.
        this.anonymisedAt = at;
        this.deletedAt = at;
    }

    /**
     * §4.11's AD-04: stops this account.
     *
     * <p><strong>Reversible, unlike a campaign's suspension.</strong> A campaign that was
     * stopped cannot go back to {@code LIVE} because its funding window has moved on; an
     * account has no window, and a ban made in error has to be undoable or the mistake is
     * permanent. {@link #reinstate} is the way back and it clears all three columns
     * together, because {@code users_suspension_is_whole} refuses any other combination.
     *
     * <p><strong>The reason is required and is what the person is told.</strong> An
     * account that cannot sign in and is not told why produces a support ticket every
     * time, and an appeal nobody can answer.
     *
     * <p>Suspending twice keeps the first decision. The second call would otherwise
     * rewrite who took it and when, which is exactly what an appeal is read from.
     *
     * @param by the staff account taking the decision, never this account itself —
     *     {@code users_suspension_has_another_author} refuses that, because a
     *     self-reference makes "who did this" answer with the person it was done to
     */
    public void suspend(Instant at, UUID by, String reason) {
        if (this.suspendedAt != null) {
            return;
        }
        this.suspendedAt = Objects.requireNonNull(at, "A suspension happened at a time");
        this.suspendedBy = Objects.requireNonNull(by, "A suspension has an author");
        this.suspensionReason = Objects.requireNonNull(reason, "A suspension has a reason");
    }

    /** Lets the account back in. All three columns or none — see {@link #suspend}. */
    public void reinstate() {
        this.suspendedAt = null;
        this.suspendedBy = null;
        this.suspensionReason = null;
    }

    /** Whether this account is stopped. What sign-in refuses on. */
    public boolean isSuspended() {
        return suspendedAt != null;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public UUID getSuspendedBy() {
        return suspendedBy;
    }

    public String getSuspensionReason() {
        return suspensionReason;
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

    public Instant getDeletionRequestedAt() {
        return deletionRequestedAt;
    }

    public Instant getDeletionScheduledAt() {
        return deletionScheduledAt;
    }

    public Instant getAnonymisedAt() {
        return anonymisedAt;
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
