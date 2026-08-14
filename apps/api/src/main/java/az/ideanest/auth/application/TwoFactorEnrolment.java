package az.ideanest.auth.application;

/**
 * What starting an enrolment hands back, once.
 *
 * @param secret the shared secret in base32, for somebody who cannot scan a
 *     picture and types it in instead
 * @param otpauthUri the {@code otpauth://totp/...} URI the client renders as a
 *     QR code. Rendered on the client rather than here: a server-side QR image
 *     means the secret travels through an image pipeline, a cache, and possibly
 *     a CDN, and an authenticator only ever needed the string
 */
public record TwoFactorEnrolment(String secret, String otpauthUri) {
}
