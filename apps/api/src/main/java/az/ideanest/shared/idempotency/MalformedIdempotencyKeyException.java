package az.ideanest.shared.idempotency;

/**
 * An {@code Idempotency-Key} that is not a UUID.
 *
 * <p>Told apart from {@link MissingIdempotencyKeyException} because they are
 * different client mistakes with different fixes: one is a header nobody added,
 * the other is a header whose value came from the wrong generator. A single code
 * covering both would send a developer looking for the first when they have the
 * second.
 */
public class MalformedIdempotencyKeyException extends RuntimeException {

    public MalformedIdempotencyKeyException() {
        // **No cause, deliberately.** What raises this is UUID.fromString refusing
        // the value, which is an IllegalArgumentException — and Spring's exception
        // resolver falls back to an exception's *cause* when an advice has no handler
        // for the exception itself. Carrying the cause would therefore let a module
        // advice with a broad IllegalArgumentException handler answer this before the
        // shared advice ever sees it, and the client would be told "invalid request"
        // instead of which header is wrong. There is nothing in the cause worth
        // keeping: the message it carries is the value of the key.
        super("An Idempotency-Key is a UUID");
    }
}
