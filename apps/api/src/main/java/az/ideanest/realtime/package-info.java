/**
 * §12.1: what a page is told while somebody is looking at it.
 *
 * <p><strong>This module has no tables and decides nothing.</strong> Every fact it broadcasts
 * has already been committed by the module that owns it and published through §8.3's outbox;
 * this one listens, aggregates, and pushes. That is why it can be switched off — a deployment
 * with {@code ideanest.realtime.enabled: false} loses live counters and nothing else, and no
 * other module notices.
 *
 * <p>It is also why nothing here is a source of truth for a client. A page renders from the
 * server-rendered document and the ordinary API; what arrives over a socket is a nudge —
 * "the total moved by this much", "there are three new comments". A client that reconstructed
 * state from the socket alone would be a client that is wrong after every disconnection, and
 * there is no replay.
 *
 * <h2>What is built, and what §12.1 still describes</h2>
 *
 * <p>Two of §12.1's six channels: {@code project:{id}} for the pledge counter and
 * {@code project:{id}:comments}. Both are public, which is what makes them the two that can be
 * built first — {@code user:{id}} and {@code project:{id}:dashboard} carry a person's own
 * notifications and a creator's live metrics, and neither can be served until the socket
 * authenticates, which is its own change. {@code project:{id}:updates} has a producer and no
 * reader asking for it.
 *
 * <p><strong>One replica, and the bound is stated rather than hidden.</strong> §16 names a
 * Redis relay so that a broadcast reaches sockets held by every instance; no Redis is deployed,
 * so {@code RealtimeBroadcaster} pushes to the sessions <em>this</em> process holds. On more
 * than one replica a reader is therefore told about roughly one event in N. That is a degraded
 * live counter rather than a wrong page — the numbers in the document are still correct and
 * still refresh on navigation — and it is why nothing on the platform depends on this module
 * for correctness.
 */
package az.ideanest.realtime;
