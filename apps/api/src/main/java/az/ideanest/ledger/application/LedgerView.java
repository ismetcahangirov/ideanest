package az.ideanest.ledger.application;

import java.util.List;

/**
 * One page of the ledger, with the standing position it is a page of.
 *
 * <p><strong>The balances are on the page rather than on their own endpoint</strong>,
 * because they are the only thing that makes the page mean anything. Twenty-five postings
 * out of a hundred thousand say nothing about whether escrow holds what it should; the
 * totals do, and a screen that had to make a second request for them would render the
 * postings first and the only number worth reading second.
 *
 * @param scope what was asked for, echoed so a client cannot file a stale response under
 *     the wrong filter
 * @param postings newest first, both sides of each shown together
 * @param nextCursor the last posting's {@code lastEntryId}, to send as {@code after} for
 *     the next page, or null when this was the last one
 * @param balances every account's net, per currency, for the campaign in {@link #scope()}
 *     or for the whole platform when it names none. <strong>Unaffected by the scope's
 *     account filter</strong>, and deliberately: narrowing the postings to escrow does not
 *     make the other five accounts stop existing, and a balance panel that showed one line
 *     would read as "this is the whole ledger"
 */
public record LedgerView(
        LedgerScope scope, List<LedgerPosting> postings, Long nextCursor, List<LedgerBalance> balances) {

    public LedgerView {
        postings = List.copyOf(postings);
        balances = List.copyOf(balances);
    }
}
