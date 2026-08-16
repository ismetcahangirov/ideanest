package az.ideanest.shared.idempotency;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One key, and what was answered the first time it was used.
 *
 * <p>The row has two lives. It is inserted as a <strong>claim</strong> — the key,
 * the account, the operation, and a fingerprint of the request, with no response —
 * and that insert is what serialises two identical requests arriving at once,
 * because {@code idempotency_keys_account_key_key} lets exactly one of them
 * succeed. When the work has been done it becomes a <strong>record</strong>: the
 * status and the exact bytes that were sent, which is what a replay is answered
 * with.
 *
 * <p><strong>Nothing here is pledge-specific</strong>, deliberately.
 * {@code ModuleBoundaryTests} names idempotency as one of the things {@code shared}
 * is for, and #56's edits and epic #59's charges need the same guarantee — a second
 * copy of this per module would be a second set of races to get right.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Keys are scoped to one account, so nobody can replay another's request. See V18. */
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "operation", nullable = false, updatable = false)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /**
     * SHA-256 of the operation and the parsed request.
     *
     * <p>A fingerprint and not the request: the only question ever asked of it is
     * whether this is the same request as last time, and keeping the body would put
     * what somebody bought and how much they gave in a second table with a
     * different retention rule from the pledge it describes.
     */
    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    /** Null while the first request is still running. */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** The bytes that were sent, so a replay is byte-identical rather than re-serialised. */
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** §17.2: keys are retained 24 hours. Swept by {@link IdempotencyKeySweeper}. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
        // JPA.
    }

    /**
     * A claim on this key by this account, with no answer recorded yet.
     *
     * <p>The expiry is passed in rather than computed here, for V17's reason: the
     * retention is configuration and the instant comes from the injected
     * {@code Clock}, so a test can ask what happens a day later without waiting a
     * day.
     */
    public static IdempotencyRecord claim(
            UUID accountId,
            String operation,
            IdempotencyKey key,
            String requestFingerprint,
            Instant expiresAt) {

        IdempotencyRecord record = new IdempotencyRecord();
        record.id = Identifiers.newIdentifier();
        record.accountId = Objects.requireNonNull(accountId, "A key is spent by an account");
        record.operation = Objects.requireNonNull(operation, "A key is spent on an operation");
        record.idempotencyKey = Objects.requireNonNull(key, "There is no key to claim").value();
        record.requestFingerprint =
                Objects.requireNonNull(requestFingerprint, "A claim carries a fingerprint of its request");
        record.expiresAt = Objects.requireNonNull(expiresAt, "A key is retained for a bounded time");
        return record;
    }

    /**
     * Records the answer, which is what every later replay of this key is given.
     *
     * <p>Only ever called for a success — see V18's
     * {@code idempotency_keys_outcome_is_a_success} and {@link IdempotentRequests},
     * which releases the claim instead when the work failed.
     */
    public void completeWith(int status, String body, Instant at) {
        this.responseStatus = status;
        this.responseBody = Objects.requireNonNull(body, "A recorded response has a body");
        this.completedAt = Objects.requireNonNull(at, "A recorded response happened at a time");
    }

    /** Whether the first request finished and left something to replay. */
    public boolean isComplete() {
        return responseStatus != null && responseBody != null;
    }

    /** Whether this key was spent on the same request that is being made now. */
    public boolean describes(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    /** The recorded answer. Only meaningful once {@link #isComplete()}. */
    public RecordedResponse recordedResponse() {
        return new RecordedResponse(responseStatus, responseBody, true);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof IdempotencyRecord record && Objects.equals(id, record.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No key and no body: the key is a credential for the response recorded
        // against it, and the body is somebody's pledge.
        return "IdempotencyRecord[id=" + id + ", operation=" + operation + ", complete=" + isComplete() + "]";
    }
}
