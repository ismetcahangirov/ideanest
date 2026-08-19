package az.ideanest.notification.application;

/**
 * Two requests wrote the same switch at the same moment, and one of them lost.
 *
 * <p>{@code notification_preferences_key} is {@code UNIQUE (user_id, category, channel)},
 * and this class exists because the write is a read followed by an insert: two settings
 * pages saving the same switch for the first time both find no row, both insert, and the
 * second one meets the index.
 *
 * <p><strong>409 and not 500.</strong> {@code UpdateNumberContendedException} makes the
 * same argument one module over: nothing is broken, the caller's request was valid, and
 * repeating it succeeds — the second attempt finds the row the first one wrote and updates
 * it instead of inserting. A 500 would tell a client to give up and an operator to
 * investigate, and neither is right.
 *
 * <p>Not retried here, deliberately. A retry inside the transaction that just failed
 * cannot work — the transaction is already marked rollback-only by the integrity error —
 * and a retry loop around it belongs to whoever can decide how long the caller should
 * wait. It is also very nearly unreachable in practice: one account has to save the same
 * switch twice concurrently, which is a double-tap rather than a load pattern.
 */
public class PreferenceContendedException extends RuntimeException {

    public PreferenceContendedException(String message, Throwable cause) {
        super(message, cause);
    }
}
