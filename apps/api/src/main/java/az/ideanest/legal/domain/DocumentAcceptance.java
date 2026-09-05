package az.ideanest.legal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * That a named account accepted a named version of a document, at a named time — V65's
 * row, issue #425.
 *
 * <h2>Every column is {@code updatable = false}, and there are no mutators</h2>
 *
 * <p>An acceptance is not a state, it is a thing that happened. There is no correcting one
 * and no withdrawing one: somebody who no longer agrees to the terms stops using the
 * platform, which is a different fact and gets its own row when they accept a later
 * version. A settable column here would be a way to make the record say something other
 * than what happened, which is the only property this table has.
 *
 * <p>The same reasoning as {@code AuditEntry}, and for once it is enforced in Java as well
 * as in the database — V65 does not put an append-only trigger on this table, because
 * {@code ON DELETE CASCADE} from {@code users} has to be able to remove rows and a trigger
 * refusing DELETE would break every §17.4 erasure.
 *
 * <h2>What is recorded beyond the two identifiers</h2>
 *
 * <p>The address and the user agent. Weak evidence, both of them, and corroborating rather
 * than proving: they say the acceptance came from somewhere consistent with the account's
 * other activity, which is what makes a denial checkable. They are taken from the request
 * by {@code AuditEnvironment} rather than passed by a caller, for that class's reason — an
 * actor who could name their own source address would be writing the alibi as well as the
 * record.
 *
 * <p>{@link #getSignatureId} is null and stays null until #429. A tick is an acceptance; a
 * SİMA İmza signature is an acceptance with the legal force of a handwritten one, and the
 * creator agreement is where the difference is spent.
 */
@Entity
@Table(name = "document_acceptances")
public class DocumentAcceptance {

    /** {@code audit_logs.user_agent}'s bound, and V65's, applied where the value is taken. */
    private static final int USER_AGENT_MAX = 512;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    /**
     * {@code inet}, which validates the value and normalises IPv6 — the same decision, and
     * the same mapping, as {@code audit_logs.source_address}.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "signature_id", updatable = false)
    private UUID signatureId;

    protected DocumentAcceptance() {
        // JPA.
    }

    /**
     * Somebody has just agreed to something.
     *
     * @param documentId the governing version — see {@code AgreementInForce} on why an
     *     acceptance points at the Azerbaijani row whichever language was read
     */
    public static DocumentAcceptance of(
            UUID id, UUID userId, UUID documentId, Instant acceptedAt, String ipAddress, String userAgent) {

        DocumentAcceptance acceptance = new DocumentAcceptance();
        acceptance.id = Objects.requireNonNull(id, "id");
        acceptance.userId = Objects.requireNonNull(userId, "userId");
        acceptance.documentId = Objects.requireNonNull(documentId, "documentId");
        acceptance.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        acceptance.ipAddress = ipAddress;
        acceptance.userAgent = truncated(userAgent);
        return acceptance;
    }

    /**
     * Truncated rather than refused.
     *
     * <p>A user agent is whatever the client sent, it is corroborating evidence rather than
     * the record itself, and an over-long one is a header a browser extension appended to —
     * not an attack and not a reason to refuse the acceptance the person just made.
     */
    private static String truncated(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= USER_AGENT_MAX ? userAgent : userAgent.substring(0, USER_AGENT_MAX);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public UUID getSignatureId() {
        return signatureId;
    }
}
