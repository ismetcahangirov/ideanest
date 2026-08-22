package az.ideanest.ledger.application;

import az.ideanest.shared.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced set of entries, built whole and written whole.
 *
 * <p><strong>This type exists so that an unbalanced posting cannot be constructed,
 * let alone written.</strong> V41's deferred constraint trigger is the enforcement
 * and it is not going anywhere — a support script that inserts entries directly is
 * still refused — but a trigger that fires at {@code COMMIT} reports the failure at
 * the commit, naming no line anybody wrote. So the rule is checked twice: here, where
 * the mistake is, and in PostgreSQL, where the guarantee is.
 *
 * <p><strong>Why the whole posting is one object rather than a sequence of calls.</strong>
 * A {@code ledger.debit(...)} followed by a {@code ledger.credit(...)} is two
 * statements with a return in between, and every conditional between them is a path
 * on which only half of a movement of money is recorded. Handing over the complete
 * posting makes "these entries are one fact" a property of the type instead of a
 * property of whoever wrote the method.
 *
 * <h2>Building one</h2>
 *
 * <pre>{@code
 * Posting.of(transactionId, projectId)
 *         .debit(LedgerAccount.ESCROW, total)
 *         .credit(LedgerAccount.PSP_FEE, processingFee)
 *         .credit(LedgerAccount.PLATFORM_FEE, platformFee)
 *         .credit(LedgerAccount.creator(creatorId), net)
 *         .build();
 * }</pre>
 *
 * <p>The builder is mutable and the result is not; a caller adds lines under whatever
 * conditions it likes — §9.5's tax line only exists when there is tax — and the
 * balance is judged once, at {@link Builder#build()}.
 */
public record Posting(UUID transactionId, UUID projectId, String currency, List<Line> lines) {

    public Posting {
        Objects.requireNonNull(transactionId, "A posting belongs to a transaction; that is what balances");
        Objects.requireNonNull(projectId, "A posting belongs to a campaign");
        Objects.requireNonNull(currency, "A posting is in a currency");
        lines = List.copyOf(Objects.requireNonNull(lines, "A posting is its lines"));

        if (lines.size() < 2) {
            // One line cannot balance against anything, and zero lines is a movement of
            // money that says nothing moved -- which is not a posting, it is the absence
            // of one, and the caller that produced it meant something else.
            throw new UnbalancedPostingException(
                    transactionId, "A posting has at least two lines, and this one has " + lines.size());
        }

        Money net = Money.zero(currency);
        for (Line line : lines) {
            if (!line.amount().currency().equals(currency)) {
                // §21.2: there is no rate at which one currency balances another, so this
                // is not a rounding question. Caught here as well as by V41's per-currency
                // grouping, because the message here can name the line.
                throw new UnbalancedPostingException(
                        transactionId,
                        "A posting is in one currency: " + currency + ", but " + line.account() + " is in "
                                + line.amount().currency());
            }
            net = line.direction() == EntryDirection.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
        }
        if (!net.isZero()) {
            throw new UnbalancedPostingException(
                    transactionId, "Debits exceed credits by " + net + " on transaction " + transactionId);
        }
    }

    /** A posting under construction. See the class comment for why this shape. */
    public static Builder of(UUID transactionId, UUID projectId) {
        return new Builder(transactionId, projectId);
    }

    /**
     * One entry: an account, a direction, and how much.
     *
     * @param account one of §7.2's six
     * @param direction which way
     * @param amount how much, always positive. The direction carries the sign, so a
     *     negative amount here would be a credit written by somebody who did not know
     *     the direction column existed — and it would balance against nothing
     */
    public record Line(LedgerAccount account, EntryDirection direction, Money amount) {

        public Line {
            Objects.requireNonNull(account, "An entry belongs to an account");
            Objects.requireNonNull(direction, "An entry goes one way or the other");
            Objects.requireNonNull(amount, "An entry is an amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "An entry moves a positive amount and the direction carries the sign; " + account + " has "
                                + amount);
            }
        }
    }

    /** Accumulates lines; {@link #build()} is where the balance is judged. */
    public static final class Builder {

        private final UUID transactionId;
        private final UUID projectId;
        private final List<Line> lines = new ArrayList<>(4);
        private String currency;

        private Builder(UUID transactionId, UUID projectId) {
            this.transactionId = transactionId;
            this.projectId = projectId;
        }

        public Builder debit(LedgerAccount account, Money amount) {
            return add(account, EntryDirection.DEBIT, amount);
        }

        public Builder credit(LedgerAccount account, Money amount) {
            return add(account, EntryDirection.CREDIT, amount);
        }

        /**
         * The same, unless the amount is nothing.
         *
         * <p>§9.5 has lines that are conditionally present — no tax is collected today,
         * and a fee schedule may set a rate to zero — and V41 refuses a zero-amount
         * entry because "no fee was charged" is expressed by the absence of the row.
         * Without this the caller writes the same {@code if} at four call sites, and
         * the one it forgets fails at a commit.
         */
        public Builder creditIfAny(LedgerAccount account, Money amount) {
            return amount == null || amount.isZero() ? this : credit(account, amount);
        }

        /** As {@link #creditIfAny}, for the other direction. */
        public Builder debitIfAny(LedgerAccount account, Money amount) {
            return amount == null || amount.isZero() ? this : debit(account, amount);
        }

        private Builder add(LedgerAccount account, EntryDirection direction, Money amount) {
            Objects.requireNonNull(amount, "An entry is an amount");
            if (currency == null) {
                currency = amount.currency();
            }
            lines.add(new Line(account, direction, amount));
            return this;
        }

        /**
         * The finished posting.
         *
         * @throws UnbalancedPostingException when the lines do not add to zero, when
         *     they are not all in one currency, or when there are fewer than two
         */
        public Posting build() {
            if (currency == null) {
                throw new UnbalancedPostingException(transactionId, "A posting with no lines is not a posting");
            }
            return new Posting(transactionId, projectId, currency, lines);
        }
    }
}
