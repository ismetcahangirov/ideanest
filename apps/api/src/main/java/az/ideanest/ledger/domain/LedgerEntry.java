package az.ideanest.ledger.domain;

import az.ideanest.ledger.application.EntryDirection;
import az.ideanest.ledger.application.LedgerAccount;
import az.ideanest.ledger.application.Posting;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.money.MoneyAmountConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One line of §7.2's double entry: an amount, an account, and which way it went.
 *
 * <p><strong>Every field is {@code updatable = false} and there is no setter.</strong>
 * V41 makes that true of the database with a statement-level trigger, and this is the
 * same fact expressed where a developer meets it: a ledger that can be edited is not
 * a ledger. A correction is a reversing posting — {@link EntryDirection#opposite()} —
 * which leaves both the mistake and the correction visible, and that is what an audit
 * of the platform's money is made of.
 *
 * <p><strong>Entries are never loaded to be changed, only to be added up.</strong>
 * There is deliberately no {@code @Version}: optimistic locking answers "did somebody
 * else change this row while I held it", and nothing can change this row. Two writers
 * posting to one transaction concurrently is not a conflict either — V41's deferred
 * trigger judges the transaction at commit and refuses whichever combination fails to
 * balance.
 *
 * <p><strong>{@code signed_amount} is not mapped.</strong> It is a generated column
 * and the balance invariant is a sum over it in SQL; mapping it would put a second,
 * derived answer beside {@link #amount} and {@link #direction} for Java to keep in
 * step with, which is the situation the generated column exists to prevent. Anything
 * in Java that needs the signed value computes it from the direction, which is one
 * expression rather than one column.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    /**
     * V41's {@code bigint GENERATED ALWAYS AS IDENTITY}.
     *
     * <p>{@code IDENTITY} and not a sequence generator, so Hibernate takes the value
     * PostgreSQL assigned rather than allocating from a pool of its own — the insertion
     * order is worth keeping, because reading a posting back in the order it was
     * written is how anybody checks it by eye. The cost is that inserts cannot be
     * batched, which is the right trade for a table whose largest posting is five rows.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The unit that balances. See V41 and {@link Posting}. */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    /**
     * One of §7.2's six, as text.
     *
     * <p>Stored through {@link LedgerAccount#name()} rather than as an
     * {@code @Enumerated}, because one of the six is parameterised by a creator — see
     * {@link LedgerAccount} for why that rules out an enum.
     */
    @Column(name = "account", nullable = false, updatable = false)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private EntryDirection direction;

    /**
     * How much, always positive; {@link #direction} carries the sign.
     *
     * <p>{@link MoneyAmountConverter} rather than a bare {@link BigDecimal}, so that an
     * amount with a place {@code numeric(14,2)} cannot hold is refused on the way in
     * instead of being rounded by PostgreSQL — on this table above all others, since
     * these rows are what a payout is computed from.
     */
    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    /** Which campaign's books. Denormalised from the transaction; see V41. */
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * When the entry was written, assigned by PostgreSQL.
     *
     * <p>The database's clock and not the application's, like every other
     * {@code created_at} in this schema: it records a fact about the row rather than
     * driving a rule. What a posting <em>means</em> — when the charge happened — is
     * {@code transactions.created_at}, and the two are the same commit.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // Hibernate's.
    }

    private LedgerEntry(UUID transactionId, UUID projectId, Posting.Line line) {
        this.transactionId = transactionId;
        this.projectId = projectId;
        this.account = line.account().name();
        this.direction = line.direction();
        this.amount = line.amount().amount();
        this.currency = line.amount().currency();
    }

    /**
     * One line of a posting, as a row.
     *
     * <p>Package-private construction through {@link Posting} only: the point of the
     * posting type is that entries are built whole and balanced, and a public factory
     * here would be the door around it.
     */
    static LedgerEntry of(Posting posting, Posting.Line line) {
        return new LedgerEntry(posting.transactionId(), posting.projectId(), line);
    }

    /** Every entry of one posting, in the order the posting listed them. */
    public static List<LedgerEntry> allOf(Posting posting) {
        return posting.lines().stream().map(line -> of(posting, line)).toList();
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public LedgerAccount getAccount() {
        return new LedgerAccount(account);
    }

    public EntryDirection getDirection() {
        return direction;
    }

    /** The amount and its currency, assembled at the entity's edge. See {@code MoneyAmountConverter}. */
    public Money getAmount() {
        return Money.of(amount, currency);
    }

    /**
     * The amount with the direction folded in: what V41's {@code signed_amount} holds
     * and what the invariant sums.
     *
     * <p>Computed rather than read back, so that this value cannot disagree with the
     * two fields it is made of even on an entity that has not been flushed.
     */
    public Money getSignedAmount() {
        Money value = getAmount();
        return direction == EntryDirection.DEBIT ? value : value.negated();
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
