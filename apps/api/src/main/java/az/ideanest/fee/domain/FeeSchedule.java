package az.ideanest.fee.domain;

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
 * One set of terms, valid over a window — V49's row, issue #311.
 *
 * <p><strong>The only mutable column is {@code effectiveTo}</strong>, and that is the
 * whole shape of this table: a schedule is written once and later closed. Editing a rate
 * in place would silently rewrite what every past payout should have been, and §22.1 asks
 * that question with a seven-year retention rule attached — V49's header has the
 * argument. So {@link #close} is the only method that changes anything, and every other
 * column is {@code updatable = false} so a dirty-checked flush cannot emit an UPDATE the
 * reviewer never agreed to.
 *
 * <p>The rates are fractions rather than percentages: {@code 0.05000} is five percent.
 * One representation, chosen because it is the one that gets multiplied — a percentage
 * would be divided by a hundred at every call site, and the call site that forgets is a
 * fee a hundred times too large.
 */
@Entity
@Table(name = "fee_schedules")
public class FeeSchedule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private FeeScope scope;

    @Column(name = "scope_ref", updatable = false)
    private UUID scopeRef;

    @Column(name = "platform_rate", nullable = false, updatable = false)
    private BigDecimal platformRate;

    @Column(name = "processing_rate", nullable = false, updatable = false)
    private BigDecimal processingRate;

    @Column(name = "processing_fixed", nullable = false, updatable = false)
    private BigDecimal processingFixed;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    /** The one column that moves. Null while this schedule is in force. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "note", nullable = false, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected FeeSchedule() {
        // Hibernate.
    }

    public FeeSchedule(
            UUID id,
            FeeScope scope,
            UUID scopeRef,
            BigDecimal platformRate,
            BigDecimal processingRate,
            BigDecimal processingFixed,
            String currency,
            Instant effectiveFrom,
            String note,
            UUID createdBy) {

        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.scopeRef = scopeRef;
        this.platformRate = Objects.requireNonNull(platformRate, "platformRate");
        this.processingRate = Objects.requireNonNull(processingRate, "processingRate");
        this.processingFixed = Objects.requireNonNull(processingFixed, "processingFixed");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.note = Objects.requireNonNull(note, "note");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");

        // Asserted here as well as by V49's CHECK, so that a caller assembling one in a
        // test finds out at the constructor rather than at the flush.
        if (scope.needsReference() == (scopeRef == null)) {
            throw new IllegalArgumentException(
                    "A " + scope + " schedule " + (scope.needsReference() ? "names" : "names nothing"));
        }
    }

    /**
     * Ends this schedule's window.
     *
     * <p>Called inside the transaction that opens its successor — see
     * {@code FeeSchedules.replace}. Two statements rather than one because V49's partial
     * unique index permits exactly one open schedule per scope, so the close has to
     * commit with the open or neither.
     */
    public void close(Instant at) {
        this.effectiveTo = Objects.requireNonNull(at, "at");
    }

    /** Whether this schedule was in force at that instant. Half-open: from is inclusive. */
    public boolean coversInstant(Instant instant) {
        return !instant.isBefore(effectiveFrom) && (effectiveTo == null || instant.isBefore(effectiveTo));
    }

    public UUID id() {
        return id;
    }

    public FeeScope scope() {
        return scope;
    }

    public UUID scopeRef() {
        return scopeRef;
    }

    public BigDecimal platformRate() {
        return platformRate;
    }

    public BigDecimal processingRate() {
        return processingRate;
    }

    public BigDecimal processingFixed() {
        return processingFixed;
    }

    public String currency() {
        return currency;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveTo() {
        return effectiveTo;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }
}
