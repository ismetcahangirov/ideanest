package az.ideanest.auth.application;

/**
 * An address change aimed at an address that already has an account.
 *
 * <p><strong>Saying so is not the enumeration oracle registration avoids.</strong> The
 * caller here is signed in and is telling us an address they claim to own; the answer
 * "that one is taken" costs them one probe per request against a rate-limited endpoint
 * that also emails the address they named. Registration's form is unauthenticated and
 * can be driven from a breach list, which is a different thing entirely.
 *
 * <p>Refusing out loud is also the only way the person can act on it. A change that
 * silently did nothing would leave somebody waiting for a confirmation that is never
 * coming.
 */
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException(String message) {
        super(message);
    }
}
