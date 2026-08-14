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
     * @param window the period all three are measured over
     */
    public record RateLimit(
            int registrationsPerAddress, int registrationsPerEmail, int verificationsPerAddress, Duration window) {
    }
}
