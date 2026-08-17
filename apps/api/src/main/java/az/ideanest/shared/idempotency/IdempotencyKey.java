package az.ideanest.shared.idempotency;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} header, once it has been established to be one.
 *
 * <p><strong>A type rather than a {@code String}</strong>, because the whole of
 * §10.3's guarantee rests on this value and a header is whatever a client sent.
 * Every caller of {@link IdempotentRequests} has to produce one of these, so the
 * two refusals below happen once, in one place, instead of at each endpoint that
 * remembers to check.
 *
 * <p><strong>A missing key is a refusal, not an unprotected request.</strong> The
 * tempting alternative — treat an absent header as "this client does not want
 * replay protection" — makes the guarantee opt-in for exactly the clients most
 * likely to need it, and the symptom is a backer charged twice by a mobile app on
 * a bad connection. §10.3 says required, and required is what this means.
 *
 * <p><strong>A UUID, per §10.3 and the epic's API contract.</strong> Two properties
 * matter and neither is decoration: a key has to be unguessable, because the
 * response recorded against one is somebody's pledge, and it has to be unique
 * without coordination, because every client mints its own. A caller free to send
 * {@code "1"} satisfies neither, and the request it protects is the one that moves
 * money.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "An idempotency key has a value");
    }

    /**
     * The header, validated.
     *
     * @param header the raw value, or null when the client sent none
     * @throws MissingIdempotencyKeyException when there is nothing there
     * @throws MalformedIdempotencyKeyException when there is something and it is
     *     not a UUID
     */
    public static IdempotencyKey of(String header) {
        if (header == null || header.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        String trimmed = header.trim();
        try {
            // Round-tripped through UUID rather than matched against a pattern, so
            // that the value stored is the canonical spelling of the key and two
            // requests differing only in case are one key rather than two. A client
            // that retries with "0193F2A1-..." after sending "0193f2a1-..." is
            // retrying, and being handed a second pledge for it would be the exact
            // failure this type exists to prevent.
            return new IdempotencyKey(UUID.fromString(trimmed).toString().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedIdempotencyKeyException();
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
