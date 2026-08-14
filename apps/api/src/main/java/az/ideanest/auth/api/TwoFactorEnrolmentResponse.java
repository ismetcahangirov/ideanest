package az.ideanest.auth.api;

/**
 * The enrolment, returned once and never again.
 *
 * @param secret base32, for a user who cannot scan a picture
 * @param otpauthUri what the client renders as a QR code. Rendered there rather
 *     than here: a server-rendered image would put the secret through an image
 *     pipeline and possibly a cache, and an authenticator only ever needed the
 *     string
 * @param digits how many the code has
 * @param periodSeconds how often it changes
 * @param algorithm the HMAC underneath, so a client showing the details does
 *     not hard-code them
 */
public record TwoFactorEnrolmentResponse(
        String secret, String otpauthUri, int digits, long periodSeconds, String algorithm) {
}
