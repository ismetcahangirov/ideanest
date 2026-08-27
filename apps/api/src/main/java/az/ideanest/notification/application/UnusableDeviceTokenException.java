package az.ideanest.notification.application;

/**
 * A push token that is not one the push service could have issued — issue #87.
 *
 * <p>Refused rather than stored, and the reason is a property of Expo's API rather than
 * fastidiousness: it rejects a whole batch containing one malformed token. A single bad
 * registration would therefore stop every other person in that batch being told anything,
 * so the cheapest place to refuse it is at the client that produced it, with a 400 naming
 * the field.
 *
 * <p>It carries no message about what was wrong with the value, and it never echoes the
 * value: the token is an address, and a problem detail is a document a client may log.
 */
public class UnusableDeviceTokenException extends RuntimeException {

    public UnusableDeviceTokenException() {
        super("That is not a push token this service can send to");
    }
}
