package az.ideanest.signature.application;

/**
 * No such signing session, for this caller — issue #429.
 *
 * <p>One exception for two cases: there is no session with that handle, and there is one that
 * belongs to somebody else. They are deliberately indistinguishable, because the difference is
 * only useful to somebody working out which handles exist.
 */
public class UnknownSigningSessionException extends RuntimeException {

    private final String sessionId;

    public UnknownSigningSessionException(String sessionId) {
        super("No signing session " + sessionId + " belongs to this account");
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
