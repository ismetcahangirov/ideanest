package az.ideanest.shared.idempotency;

/**
 * The first request carrying this key has not finished yet.
 *
 * <p><strong>The loser of a race is told to retry rather than made to wait</strong>,
 * and the choice is between two costs. Waiting would mean holding this request's
 * thread and its database connection for however long the winner takes — which
 * includes reserving stock and, once #55 lands, a round trip to a payment provider
 * — so a client retrying a slow request in a loop would exhaust the connection pool
 * with requests that are all going to be told the same thing. Worse, the waiter
 * cannot see the winner's answer until the winner commits, so the wait buys nothing
 * that a retry does not.
 *
 * <p>The honest reading of this status is "ask again in a moment": the client's
 * work is being done, exactly once, by the request that got there first.
 */
public class IdempotentRequestInProgressException extends RuntimeException {

    public IdempotentRequestInProgressException() {
        super("A request with this Idempotency-Key is still being processed");
    }
}
