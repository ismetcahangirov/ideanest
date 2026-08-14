package az.ideanest.auth.domain;

/**
 * Why a session stopped being valid.
 *
 * <p>Recorded rather than inferred. After a token theft the question asked is
 * "which sessions died, and which of them died because we detected reuse" — and
 * a null column answers neither.
 */
public enum SessionRevocationReason {

    /** The user signed out of this session. */
    SIGNED_OUT,

    /** The user revoked it from the device list, usually on another device. */
    USER_REVOKED,

    /**
     * A refresh token that had already been rotated was presented again. Either
     * the token was stolen or a client replayed one; both mean a copy is in
     * circulation, and the family is no longer trustworthy.
     */
    TOKEN_REUSE,

    /** The password changed, and every session predating it is now suspect. */
    PASSWORD_CHANGED,

    /** Support or trust and safety intervened. */
    ADMIN_ACTION
}
