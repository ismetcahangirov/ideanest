package az.ideanest.compliance.domain;

import az.ideanest.shared.compliance.RejectionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a creator is paid, on their account — part of issue #432.
 *
 * <p>One row per account, keyed by the account. The creator supplies it; a member of staff
 * holding {@code VERIFY_PAYOUT_DESTINATION} confirms it belongs to them; the payout reads it
 * and never accepts one from whoever is sending.
 *
 * <p><strong>The reference is a provider token and not an account number.</strong>
 * {@code PayoutRequest} explains why at length and this row does not overturn it: bank
 * details stay with the provider, and what is stored here is the same class of thing as
 * {@code StoredCard#token()}. {@link #getHolderName()} is the exception, and it is a name
 * the platform already holds twice over.
 *
 * <h2>Replacing the account clears the verification, in the entity</h2>
 *
 * <p>{@link #replaceWith} is the only way to change the token, and it resets the row to
 * {@link DestinationState#AWAITING_VERIFICATION} on the way. There is no setter that
 * changes the reference without doing so, because the bug that would produce is the exact
 * fraud this issue exists to stop — a destination verified in March, swapped in June,
 * inheriting March's verification — and it would leave a row that looked entirely normal.
 * V72 asserts the same thing as a constraint, so the invariant survives anything that
 * reaches the table by another route.
 */
@Entity
@Table(name = "payout_destinations")
public class PayoutDestination {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "reference", nullable = false)
    private String reference;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "display_hint")
    private String displayHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private DestinationState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason")
    private RejectionReason rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method")
    private DestinationVerificationMethod verificationMethod;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayoutDestination() {
    }

    private PayoutDestination(
            UUID userId, String provider, String reference, String holderName, String displayHint, Instant now) {
        this.userId = Objects.requireNonNull(userId, "A payout destination is somebody's");
        this.createdAt = at(now);
        // Not the public `replaceWith`: calling an overridable method from a constructor is
        // what `-Xlint:this-escape` refuses, and the build treats that as an error.
        replace(provider, reference, holderName, displayHint, now);
    }

    public static PayoutDestination of(
            UUID userId, String provider, String reference, String holderName, String displayHint, Instant now) {
        return new PayoutDestination(userId, provider, reference, holderName, displayHint, now);
    }

    /**
     * File a different account, and start the verification again.
     *
     * <p>Every replacement resets, including one that arrives with the same token: a creator
     * re-submitting an identical destination is either correcting the holder name or
     * probing, and neither is a reason to keep a verification that was given for what the row
     * said at the time.
     */
    public void replaceWith(String provider, String reference, String holderName, String displayHint, Instant now) {
        replace(provider, reference, holderName, displayHint, now);
    }

    private void replace(String provider, String reference, String holderName, String displayHint, Instant now) {
        this.provider = required(provider, "A payout destination names the provider that issued its token");
        this.reference = required(reference, "A payout destination has somewhere to go");
        this.holderName = required(holderName, "A payout destination names its account holder");
        this.displayHint = blankToNull(displayHint);
        this.state = DestinationState.AWAITING_VERIFICATION;
        this.rejectionReason = null;
        this.verificationMethod = null;
        this.verifiedAt = null;
        this.verifiedBy = null;
        this.updatedAt = at(now);
    }

    /**
     * Record that the holder's name is not the creator's legal name.
     *
     * <p>Separate from {@link #reject}, although V58's vocabulary would let it be a rejection
     * with {@code MISMATCHED_NAME} on it. The difference is who decided: this is the platform
     * comparing two strings at the moment the creator saved, and a rejection is a person
     * looking at evidence. Collapsing them would make an automatic comparison read, in the
     * console and in the trail, as though a reviewer had refused something.
     */
    public void nameDidNotMatch(Instant now) {
        this.state = DestinationState.NAME_MISMATCH;
        this.rejectionReason = null;
        this.verificationMethod = null;
        this.verifiedAt = null;
        this.verifiedBy = null;
        this.updatedAt = at(now);
    }

    /** Confirmed, by whom, how, and when. */
    public void verified(UUID verifierId, DestinationVerificationMethod method, Instant now) {
        this.verifiedBy = Objects.requireNonNull(verifierId, "A verification has somebody's name on it");
        this.verificationMethod = Objects.requireNonNull(method, "A verification says how it was done");
        this.state = DestinationState.VERIFIED;
        this.rejectionReason = null;
        this.verifiedAt = at(now);
        this.updatedAt = at(now);
    }

    /** Refused, with a reason the creator can be shown. */
    public void reject(RejectionReason reason, Instant now) {
        this.rejectionReason = Objects.requireNonNull(reason, "A refusal says why");
        this.state = DestinationState.REJECTED;
        this.verificationMethod = null;
        this.verifiedAt = null;
        this.verifiedBy = null;
        this.updatedAt = at(now);
    }

    /** Whether this row was tokenised by the provider a payout is about to be sent through. */
    public boolean issuedBy(String candidate) {
        return candidate != null && provider.equalsIgnoreCase(candidate.trim());
    }

    private static Instant at(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getReference() {
        return reference;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getDisplayHint() {
        return displayHint;
    }

    public DestinationState getState() {
        return state;
    }

    public Optional<RejectionReason> rejection() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<DestinationVerificationMethod> verifiedHow() {
        return Optional.ofNullable(verificationMethod);
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The reference, unreadable. What a log line is allowed to contain.
     *
     * <p>{@code PayoutRequest.toString} redacts the same value one layer down, and both are
     * needed: an entity that printed its token would put it in a Hibernate log without
     * anything having gone wrong.
     */
    @Override
    public String toString() {
        return "PayoutDestination[user=" + userId + ", provider=" + provider + ", state=" + state
                + ", reference=<redacted>]";
    }
}
