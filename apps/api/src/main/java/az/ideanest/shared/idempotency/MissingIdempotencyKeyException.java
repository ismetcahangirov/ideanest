package az.ideanest.shared.idempotency;

/**
 * No {@code Idempotency-Key} on a request that requires one.
 *
 * <p>A 400 and not a silent pass. See {@link IdempotencyKey} for why the
 * permissive reading of an absent header is the dangerous one.
 */
public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException() {
        super("This request requires an Idempotency-Key header");
    }
}
