package az.ideanest.payout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One signature on a payout — V55, issues #69 and #306.
 *
 * <p><strong>A row rather than a column, and V55's header has the three reasons.</strong>
 * The one that matters most is that the rule is "two <em>different</em> people": with
 * {@code approved_by_1} and {@code approved_by_2} that is a {@code CHECK} comparing them,
 * which silently passes whenever the second is null. With rows it is the primary key,
 * which cannot.
 *
 * <p>Immutable, like every other record of a decision on this platform. Withdrawing an
 * approval deletes the row, which leaves the audit trail as the only account of it — and
 * that is the right place for "somebody signed and then unsigned".
 */
@Entity
@Table(name = "payout_approvals")
@IdClass(PayoutApproval.Key.class)
public class PayoutApproval {

    @Id
    @Column(name = "payout_id", nullable = false, updatable = false)
    private UUID payoutId;

    @Id
    @Column(name = "approver_id", nullable = false, updatable = false)
    private UUID approverId;

    @Column(name = "approved_at", nullable = false, insertable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "note", updatable = false)
    private String note;

    protected PayoutApproval() {
        // Hibernate.
    }

    public PayoutApproval(UUID payoutId, UUID approverId, String note) {
        this.payoutId = Objects.requireNonNull(payoutId, "payoutId");
        this.approverId = Objects.requireNonNull(approverId, "approverId");
        this.note = note;
    }

    public UUID payoutId() {
        return payoutId;
    }

    public UUID approverId() {
        return approverId;
    }

    public Instant approvedAt() {
        return approvedAt;
    }

    public String note() {
        return note;
    }

    /** The composite key. Required by JPA; carries no behaviour. */
    public static class Key implements Serializable {

        private UUID payoutId;

        private UUID approverId;

        protected Key() {
        }

        public Key(UUID payoutId, UUID approverId) {
            this.payoutId = payoutId;
            this.approverId = approverId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(payoutId, key.payoutId)
                    && Objects.equals(approverId, key.approverId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(payoutId, approverId);
        }
    }
}
