package az.ideanest.pledge.domain;

import az.ideanest.shared.Identifiers;
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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A purchase made after the campaign closed — §4.8's PM-09, PM-10 and PM-16 (#76).
 *
 * <p><strong>Beside the pledge and never inside it.</strong> V39 carries the argument
 * at length: §5.1 judged the campaign by comparing what it raised against its goal at
 * its deadline, so rewriting {@code base_amount} months later because somebody bought
 * a second mug would change a number the platform has already reported and frozen. And
 * the money moves separately — the campaign's pledges are collected in one batch and
 * this is charged on its own, which is what the issue means by "a separate
 * transaction".
 *
 * <p><strong>Nothing here has ever been charged.</strong> {@code collectedAt} is null
 * on every row this platform holds, because collection is epic #59 and is blocked on
 * choosing a payment provider (#60). PM-16 — charging the additional amount — is
 * therefore recorded rather than performed, and a stub that marked a supplement
 * collected would tell a creator money had arrived that has not.
 *
 * <p>The amount is always positive. A downgrade is a refund, which is #67's, and
 * recording one here as a negative amount would make a future collection run pay
 * somebody by accident.
 */
@Entity
@Table(name = "pledge_supplements")
public class PledgeSupplement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private SupplementKind kind;

    @Column(name = "from_reward_tier_id", updatable = false)
    private UUID fromRewardTierId;

    @Column(name = "to_reward_tier_id", updatable = false)
    private UUID toRewardTierId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    /** Epic #59's, and null on every row until it lands. */
    @Column(name = "collected_at")
    private Instant collectedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PledgeSupplement() {
        // JPA.
    }

    /** PM-09: the pledge moved to a better tier, and this is the difference. */
    public static PledgeSupplement upgrade(
            UUID pledgeId, UUID projectId, UUID fromTierId, UUID toTierId, BigDecimal amount, String currency) {

        PledgeSupplement supplement = of(pledgeId, projectId, SupplementKind.UPGRADE, amount, currency);
        supplement.fromRewardTierId = Objects.requireNonNull(fromTierId, "An upgrade came from a tier");
        supplement.toRewardTierId = Objects.requireNonNull(toTierId, "An upgrade went to a tier");
        return supplement;
    }

    /** PM-10: more things bought beside the reward. The lines are {@link SupplementAddon}s. */
    public static PledgeSupplement addons(UUID pledgeId, UUID projectId, BigDecimal amount, String currency) {
        return of(pledgeId, projectId, SupplementKind.ADDONS, amount, currency);
    }

    private static PledgeSupplement of(
            UUID pledgeId, UUID projectId, SupplementKind kind, BigDecimal amount, String currency) {

        PledgeSupplement supplement = new PledgeSupplement();
        supplement.id = Identifiers.newIdentifier();
        supplement.pledgeId = Objects.requireNonNull(pledgeId, "A supplement belongs to a pledge");
        supplement.projectId = Objects.requireNonNull(projectId, "A supplement belongs to a campaign");
        supplement.kind = kind;
        supplement.amount = Objects.requireNonNull(amount, "A purchase costs something");
        supplement.currency = Objects.requireNonNull(currency, "An amount has a currency");
        return supplement;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPledgeId() {
        return pledgeId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public SupplementKind getKind() {
        return kind;
    }

    public UUID getFromRewardTierId() {
        return fromRewardTierId;
    }

    public UUID getToRewardTierId() {
        return toRewardTierId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PledgeSupplement supplement && Objects.equals(id, supplement.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No amount: a log line about a purchase should not be a record of what
        // somebody spent.
        return "PledgeSupplement[id=" + id + ", pledge=" + pledgeId + ", kind=" + kind + "]";
    }
}
