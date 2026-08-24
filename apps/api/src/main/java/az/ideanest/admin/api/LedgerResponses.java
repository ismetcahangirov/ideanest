package az.ideanest.admin.api;

import az.ideanest.ledger.application.LedgerBalance;
import az.ideanest.ledger.application.LedgerPosting;
import az.ideanest.ledger.application.LedgerView;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AD-05's ledger on the wire — #305.
 *
 * <p>The shape is postings and not entries, which is the whole of the issue: §7.2's
 * invariant is stated per transaction, so a response that streamed rows would be a response
 * in which the platform's central accounting rule cannot be seen. {@code LedgerPosting} has
 * the argument.
 */
final class LedgerResponses {

    private LedgerResponses() {
    }

    static View of(LedgerView view) {
        return new View(
                view.scope().account() == null ? null : view.scope().account().name(),
                view.scope().projectId(),
                view.postings().stream().map(LedgerResponses::posting).toList(),
                view.nextCursor(),
                view.balances().stream().map(LedgerResponses::balance).toList());
    }

    private static Posting posting(LedgerPosting row) {
        return new Posting(
                row.transactionId(),
                row.projectId(),
                row.createdAt(),
                row.lines().stream()
                        .map(line -> new Line(line.account(), line.direction().name(), line.amount()))
                        .toList(),
                row.balanced());
    }

    private static Balance balance(LedgerBalance row) {
        return new Balance(row.account(), row.net());
    }

    /**
     * One page of postings, and the standing position behind them.
     *
     * @param account which account was asked about, echoed and absent when none was
     * @param projectId which campaign was asked about, echoed and absent when none was
     * @param postings newest first, both sides of each shown together
     * @param nextCursor what to send as {@code after} for the next page, or absent when
     *     this was the last one. A number rather than an identifier, because
     *     {@code ledger_entries.id} is a sequence
     * @param balances every account's net, per currency, for {@link #projectId()} or for
     *     the whole platform when it is absent. <strong>Not narrowed by
     *     {@link #account()}</strong>: filtering the postings to escrow does not make the
     *     other five accounts stop existing, and a one-line balance panel would read as
     *     though it were the whole ledger
     */
    record View(
            String account,
            UUID projectId,
            List<Posting> postings,
            Long nextCursor,
            List<Balance> balances) {
    }

    /**
     * Everything the ledger wrote about one transaction.
     *
     * @param transactionId the provider call these entries explain. It joins to the payment
     *     log (#304), which is the other half of the same event: what was asked of a
     *     provider, and what it meant
     * @param projectId whose money moved
     * @param createdAt when the posting was written
     * @param lines both sides, in the order they were written. Always at least two
     * @param balanced whether the debits equal the credits. It is always true — V41 refuses
     *     a commit in which it is not — and it is on the wire so that a reader can see that
     *     rather than be told it
     */
    record Posting(UUID transactionId, UUID projectId, Instant createdAt, List<Line> lines, boolean balanced) {
    }

    /**
     * One side of one posting.
     *
     * @param account one of §7.2's six, as it is stored: {@code escrow}, {@code platform_fee},
     *     {@code psp_fee}, {@code tax_payable}, {@code refunds}, or {@code creator:{id}}
     * @param direction {@code DEBIT} or {@code CREDIT}
     * @param amount always positive; the direction carries the sign
     */
    record Line(String account, String direction, Money amount) {
    }

    /**
     * What one account holds, in one currency.
     *
     * @param account the stored account name
     * @param net debits minus credits. Positive on {@code escrow} is money the platform is
     *     holding; positive on a creator's account is money paid out beyond what was
     *     earned, which should never happen and is worth reading twice when it does
     */
    record Balance(String account, Money net) {
    }
}
