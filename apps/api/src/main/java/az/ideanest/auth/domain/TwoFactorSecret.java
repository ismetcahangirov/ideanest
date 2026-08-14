package az.ideanest.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One user's TOTP secret, and whether they ever proved they had it.
 *
 * <p><strong>Enrolment and enablement are two states of this row, not two
 * tables.</strong> A secret that has been generated but never confirmed is not
 * a second factor and must not behave like one: somebody who scans a code and
 * then loses the phone before entering a code has to be able to sign in as
 * before. {@code confirmedAt} is the only thing that decides which of the two
 * states this is, and every caller asks {@link #isConfirmed()} rather than
 * "does a row exist".
 *
 * <p>{@code lastUsedStep} is the replay defence. A code is accepted only if its
 * step is strictly greater, so one code works once — not once per thirty
 * seconds, and not three times across the skew window.
 */
@Entity
@Table(name = "user_two_factor")
public class TwoFactorSecret {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The raw secret. It cannot be hashed the way a token is: verifying a code
     * means recomputing the HMAC, so the server needs the value back. What
     * protects it is the database, and — when there is key management to do it
     * with — encryption at rest.
     */
    @Column(name = "secret", nullable = false)
    private byte[] secret;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false)
    private TwoFactorAlgorithm algorithm;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TwoFactorSecret() {
        // JPA.
    }

    private TwoFactorSecret(UUID userId, byte[] secret, TwoFactorAlgorithm algorithm) {
        this.userId = userId;
        this.secret = secret.clone();
        this.algorithm = algorithm;
    }

    /** Starts an enrolment. Unconfirmed, and therefore not yet a second factor. */
    public static TwoFactorSecret enrol(UUID userId, byte[] secret) {
        return new TwoFactorSecret(userId, requireCorrectLength(secret), TwoFactorAlgorithm.TOTP_SHA1);
    }

    /**
     * Replaces the secret of an enrolment that was never confirmed.
     *
     * <p>Refuses once it has been. Overwriting a live secret would turn "start
     * enrolling again" into "switch off the second factor", which is precisely
     * the operation that is supposed to cost a password and a code.
     */
    public void restart(byte[] newSecret) {
        if (isConfirmed()) {
            throw new IllegalStateException("Two-factor is already enabled for user " + userId);
        }
        this.secret = requireCorrectLength(newSecret).clone();
        this.algorithm = TwoFactorAlgorithm.TOTP_SHA1;
    }

    /**
     * Turns the enrolment into a second factor, recording the step that proved
     * it so that the same code cannot then be used to sign in.
     */
    public void confirm(Instant at, long provingStep) {
        if (isConfirmed()) {
            throw new IllegalStateException("Two-factor is already enabled for user " + userId);
        }
        this.confirmedAt = at;
        this.lastUsedStep = provingStep;
    }

    /**
     * Whether a code from {@code step} may still be spent.
     *
     * <p>Strictly greater, not "not equal": accepting an earlier step would let
     * a code captured a minute ago be replayed while it is still inside the skew
     * window of the current one.
     */
    public boolean isStepSpendable(long step) {
        return lastUsedStep == null || step > lastUsedStep;
    }

    /** Records that a code from this step was accepted. */
    public void spendStep(long step) {
        this.lastUsedStep = step;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getSecret() {
        return secret.clone();
    }

    public TwoFactorAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public OptionalLong getLastUsedStep() {
        return lastUsedStep == null ? OptionalLong.empty() : OptionalLong.of(lastUsedStep);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static byte[] requireCorrectLength(byte[] secret) {
        if (secret.length != Totp.SECRET_BYTES) {
            // The schema says the same thing. Failing here names the problem;
            // failing there names a constraint.
            throw new IllegalArgumentException(
                    "A TOTP secret must be " + Totp.SECRET_BYTES + " bytes, not " + secret.length);
        }
        return secret;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof TwoFactorSecret enrolment && Objects.equals(userId, enrolment.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }

    @Override
    public String toString() {
        // Never the secret, and not even its length in a way that varies: this
        // is the one value in the table that is enough on its own to mint codes.
        return "TwoFactorSecret[userId=" + userId + ", confirmed=" + isConfirmed() + "]";
    }
}
