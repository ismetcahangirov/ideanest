package az.ideanest.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about authentication that an operator might need to change without
 * a deployment, and nothing that they should not.
 *
 * @param verificationTokenTtl how long an email verification link works. Long
 *     enough to survive a mail queue and a night's sleep, short enough that a
 *     forwarded message is not a standing key to the account.
 * @param passwordMinLength minimum password length. Length is the only property
 *     that reliably resists guessing; composition rules mostly produce
 *     {@code Password1!} and a written-down note.
 * @param passwordMaxLength an upper bound, because the hash is deliberately
 *     expensive and an unbounded input is a denial of service against ourselves.
 * @param argon2 hashing parameters.
 * @param rateLimit how many attempts, and over what window.
 * @param token access and refresh token lifetimes and signing keys.
 * @param twoFactor time-based one-time passwords.
 * @param refreshCookie how the refresh token is delivered to a browser.
 * @param logVerificationLinks whether to write verification links to the log.
 *     Local development only — see {@code application-local.yml}.
 */
@ConfigurationProperties(prefix = "ideanest.auth")
public record AuthProperties(
        Duration verificationTokenTtl,
        int passwordMinLength,
        int passwordMaxLength,
        Argon2 argon2,
        RateLimit rateLimit,
        Token token,
        TwoFactor twoFactor,
        RefreshCookie refreshCookie,
        boolean logVerificationLinks) {

    /**
     * Argon2id parameters.
     *
     * <p>The defaults follow the OWASP recommendation of 19 MiB, two
     * iterations, one degree of parallelism. They are configurable because the
     * right setting is "as expensive as this machine can afford", which is a
     * property of the machine — and because raising them is how the hash keeps
     * up with hardware. Argon2's encoded output carries the parameters it was
     * produced with, so raising them rehashes on next sign-in rather than
     * locking anybody out.
     *
     * @param memoryKib memory cost in kibibytes. This is the parameter that
     *     makes a GPU no better at this than a CPU; lowering it is what turns a
     *     stolen table into a weekend of cracking.
     * @param iterations time cost.
     * @param parallelism lanes.
     * @param saltLength bytes of salt. Per password, so two identical passwords
     *     do not produce identical hashes.
     * @param hashLength bytes of output.
     */
    public record Argon2(int memoryKib, int iterations, int parallelism, int saltLength, int hashLength) {
    }

    /**
     * @param registrationsPerAddress registration attempts from one IP address
     * @param registrationsPerEmail registration attempts for one email address,
     *     counted separately so that a distributed attempt on one account is
     *     still bounded
     * @param verificationsPerAddress verification attempts from one IP address.
     *     A verification token is 256 bits, so this is not about guessing it; it
     *     is about not letting one client spend our database on the attempt
     * @param twoFactorCodesPerChallenge how many codes may be offered against
     *     one sign-in challenge. This is <em>the</em> control on a six-digit
     *     secret: with one step of skew either side, three codes are valid at
     *     any moment, so each attempt is three chances in a million and only
     *     the count of attempts keeps that a small number
     * @param twoFactorChangesPerUser how many times a user may try to switch
     *     two-factor on or off in a window. Each attempt costs an Argon2
     *     verification, so this bounds what a stolen access token can spend as
     *     much as what it can guess
     * @param window the period all of them are measured over
     */
    public record RateLimit(
            int registrationsPerAddress,
            int registrationsPerEmail,
            int verificationsPerAddress,
            int signInsPerAddress,
            int signInsPerEmail,
            int twoFactorCodesPerChallenge,
            int twoFactorChangesPerUser,
            Duration window) {
    }

    /**
     * Access and refresh tokens.
     *
     * @param issuer the {@code iss} claim, and what the decoder requires. A
     *     token minted for staging must not be accepted in production, and this
     *     is the field that says so.
     * @param audience the {@code aud} claim. Same argument, from the other side.
     * @param accessTokenTtl how long an access token is good for. Short, because
     *     it is stateless: revoking a session cannot reach a token already
     *     issued, so the window in which a revoked session still works is
     *     exactly this value. Fifteen minutes is the documented trade between
     *     that window and the cost of refreshing.
     * @param refreshTokenTtl how long a refresh token — and with it a session —
     *     survives without use.
     * @param privateKeyPem RSA private key, PKCS#8 PEM, for signing. Supplied by
     *     the secret store. Absent outside local and test, the service refuses
     *     to start rather than inventing one.
     * @param publicKeyPem the matching public key, X.509 PEM, for verifying.
     */
    public record Token(
            String issuer,
            String audience,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            String privateKeyPem,
            String publicKeyPem) {
    }

    /**
     * Time-based one-time passwords.
     *
     * @param issuer what an authenticator app shows above the six digits. It is
     *     part of the {@code otpauth://} label, so changing it after people have
     *     enrolled renames their existing entry only when they re-enrol —
     *     harmless, but not retroactive.
     * @param challengeTtl how long the proof of a correct password is good for
     *     before a code has to be entered. Minutes: it exists to carry one
     *     person across one form, and every second beyond that is a window in
     *     which a challenge captured from a proxy log is still spendable.
     */
    public record TwoFactor(String issuer, Duration challengeTtl) {
    }

    /**
     * The cookie a browser stores the refresh token in.
     *
     * <p>A refresh token in {@code localStorage} is readable by any script that
     * gets onto the page, which is what makes one cross-site scripting bug into
     * a permanent account takeover. An httpOnly cookie is not.
     *
     * @param name the cookie name
     * @param secure whether to mark it Secure. Always true except on plain-HTTP
     *     localhost, where a Secure cookie is simply never sent
     * @param path scoped to the auth endpoints, so it is not attached to every
     *     request to the API
     * @param sameSite {@code Strict}: the cookie is not sent on any cross-site
     *     request at all, which is what stops another origin from spending the
     *     victim's refresh token
     */
    public record RefreshCookie(String name, boolean secure, String path, String sameSite) {
    }
}
