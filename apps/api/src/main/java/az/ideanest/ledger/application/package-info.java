/**
 * The ledger's published contract: what a posting is, and the one door that writes it.
 *
 * <p><strong>The vocabulary is here rather than in {@code domain}, and that is a
 * boundary decision rather than a layering accident.</strong> §16.1 — checked by
 * {@code ModuleBoundaryTests} — says a module reaches another module through its
 * {@code application} layer only, so {@code LedgerAccount}, {@code EntryDirection} and
 * {@code Posting} have to live here: the payment module posts a collection, and #67,
 * #68 and #69 will post a refund, a chargeback and a payout. A vocabulary another
 * module cannot name is a vocabulary every other module would have to be handed
 * primitives instead of.
 *
 * <p>The alternative was two copies — an internal one in {@code domain} and a published
 * one here — and it is worse in the way that matters for a ledger: two definitions of
 * what an account is, kept in step by nobody, in the one part of the platform where a
 * disagreement is money that does not add up.
 *
 * <p>{@code LedgerEntry} stays in {@code domain} because it is the row, and it consumes
 * this vocabulary rather than defining a second one.
 */
package az.ideanest.ledger.application;
