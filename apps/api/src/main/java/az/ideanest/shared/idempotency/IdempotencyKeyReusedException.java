package az.ideanest.shared.idempotency;

/**
 * The same key arrived with a different request.
 *
 * <p>§10.4's {@code IDEMPOTENCY_KEY_REUSED}, and a 409 rather than a replay. The
 * client has two different intentions wearing one key, and neither answer it could
 * be given is safe: replaying the first would tell it the second request succeeded
 * when nothing of the sort happened, and executing the second would make the key
 * mean nothing.
 *
 * <p>Which of the two requests it was is deliberately not reported. The first one
 * may have been made from another device, and the response recorded against it is
 * somebody's pledge; a refusal is not the place to hand it over.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String operation;

    public IdempotencyKeyReusedException(String operation) {
        super("This Idempotency-Key was already used for a different request");
        this.operation = operation;
    }

    /** What the key was spent on the first time, which is safe to say and useful. */
    public String operation() {
        return operation;
    }
}
