package az.ideanest.ledger.application;

import java.util.UUID;

/**
 * A posting and the position it sorts at, which is the last entry written for it.
 *
 * <p>The intermediate row of the ledger's two-step read (#305). A posting is two or more
 * entries and the screen shows them together, so the page cannot be a page of entries —
 * twenty-five entries is somewhere between twelve and eight postings, and the last one on
 * the page would be cut in half. So the first query pages over <em>postings</em> and this
 * is what it returns; the second loads every entry of the postings it named.
 *
 * <p>{@code MAX(id)} rather than {@code MIN(id)} as the sort key, and either would order
 * the same way — the entries of one posting are written by one statement inside one
 * transaction, so their identifiers are consecutive and no other posting interleaves with
 * them. {@code MAX} is chosen because the cursor is then "the largest identifier this page
 * has seen", which is the sentence the keyset predicate is written in.
 *
 * @param transactionId the transaction the posting belongs to, and the key the entries are
 *     loaded back by
 * @param lastEntryId the sequence value of its last entry — the ledger's own {@code bigint}
 *     rather than a UUID, so the cursor is a number and needs no encoding
 */
public record PostingHead(UUID transactionId, Long lastEntryId) {
}
