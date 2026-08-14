package az.ideanest.auth.domain;

/**
 * How a second factor is computed.
 *
 * <p>One value today. It is stored per enrolment rather than assumed, because
 * the day a second one is added, every secret already in the table was made
 * with the first — and a column that says which is the difference between
 * migrating them and re-enrolling everybody.
 */
public enum TwoFactorAlgorithm {

    /** RFC 6238, HMAC-SHA1, six digits, thirty-second steps. See {@link Totp}. */
    TOTP_SHA1
}
