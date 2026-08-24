package az.ideanest.payment.domain;

import az.ideanest.shared.Identifiers;
import az.ideanest.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A chargeback, from the provider's notification to the outcome — V54, issues #68 and
 * #308.
 *
 * <p><strong>Opened by a webhook and never by a person.</strong> A dispute is somebody
 * else's decision that the platform is a respondent to, which is the difference between
 * this and a refund: {@code Refund} is created by staff, and this is created by
 * {@code DisputeService.notified} from what a provider sent.
 *
 * <p>The deadline is the field this row exists for. Everything else could be reconstructed
 * from the provider; a deadline that has passed cannot.
 */
@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "charge_transaction_id", nullable = false, updatable = false)
    private UUID chargeTransactionId;

    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ProviderName provider;

    @Column(name = "provider_dispute_id", nullable = false, updatable = false)
    private String providerDisputeId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "fee", nullable = false)
    private BigDecimal fee;

    @Column(name = "reason_code", nullable = false, updatable = false)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private DisputeState state;

    @Column(name = "evidence_due_at")
    private Instant evidenceDueAt;

    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Dispute() {
        // Hibernate.
    }

    private Dispute(
            UUID chargeTransactionId,
            UUID pledgeId,
            UUID projectId,
            ProviderName provider,
            String providerDisputeId,
            Money amount,
            Money fee,
            String reasonCode,
            Instant evidenceDueAt,
            Instant at) {

        this.id = Identifiers.newIdentifier();
        this.chargeTransactionId = Objects.requireNonNull(chargeTransactionId, "chargeTransactionId");
        this.pledgeId = Objects.requireNonNull(pledgeId, "pledgeId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.providerDisputeId = Objects.requireNonNull(providerDisputeId, "providerDisputeId");
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.fee = fee == null ? BigDecimal.ZERO : fee.amount();
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.state = DisputeState.OPEN;
        this.evidenceDueAt = evidenceDueAt;
        this.updatedAt = at;
    }

    /** A case the provider has just told us about. */
    public static Dispute notified(
            UUID chargeTransactionId,
            UUID pledgeId,
            UUID projectId,
            ProviderName provider,
            String providerDisputeId,
            Money amount,
            Money fee,
            String reasonCode,
            Instant evidenceDueAt,
            Instant at) {

        return new Dispute(
                chargeTransactionId,
                pledgeId,
                projectId,
                provider,
                providerDisputeId,
                amount,
                fee,
                reasonCode,
                evidenceDueAt,
                at);
    }

    /**
     * Evidence has been sent and the network is deciding.
     *
     * <p>Not terminal, and reachable again from {@link DisputeState#LOST} — see
     * {@link DisputeState} on the cycle.
     */
    public void submitted(UUID by, Instant at) {
        this.state = DisputeState.UNDER_REVIEW;
        this.handledBy = by;
        this.updatedAt = at;
    }

    /**
     * The case is over.
     *
     * <p>{@code resolvedAt} is set here and nowhere else, so V54's constraint pairing it
     * with the terminal states cannot be broken by a caller that set one and forgot the
     * other.
     *
     * @throws IllegalArgumentException for a state that is not an outcome
     */
    public void resolved(DisputeState outcome, UUID by, Instant at) {
        if (!outcome.isResolved()) {
            throw new IllegalArgumentException(outcome + " is not an outcome");
        }
        this.state = outcome;
        this.handledBy = by;
        this.resolvedAt = at;
        this.updatedAt = at;
    }

    /**
     * Puts a resolved case back in the queue, for a second presentment.
     *
     * <p>The cycle {@link DisputeState} describes. {@code resolvedAt} is cleared, because
     * the case is no longer resolved and V54 would otherwise hold a resolution date on an
     * open dispute.
     */
    public void reopened(Instant at) {
        this.state = DisputeState.OPEN;
        this.resolvedAt = null;
        this.updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID chargeTransactionId() {
        return chargeTransactionId;
    }

    public UUID pledgeId() {
        return pledgeId;
    }

    public UUID projectId() {
        return projectId;
    }

    public ProviderName provider() {
        return provider;
    }

    public String providerDisputeId() {
        return providerDisputeId;
    }

    public Money amount() {
        return Money.of(amount, currency);
    }

    public Money fee() {
        return Money.of(fee, currency);
    }

    public String reasonCode() {
        return reasonCode;
    }

    public DisputeState state() {
        return state;
    }

    public Instant evidenceDueAt() {
        return evidenceDueAt;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public UUID handledBy() {
        return handledBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
