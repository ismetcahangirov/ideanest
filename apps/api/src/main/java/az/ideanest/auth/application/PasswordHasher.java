package az.ideanest.auth.application;

import az.ideanest.auth.domain.PasswordAlgorithm;

/**
 * Hashing and verifying passwords.
 *
 * <p>An interface so that the algorithm is one implementation rather than a
 * call scattered through the service. Replacing it — because the parameters
 * were raised, or because Argon2 is one day not the answer — should be a new
 * class and a migration, not a search for every place a password is hashed.
 */
public interface PasswordHasher {

    /** The encoded hash, parameters included. */
    String hash(String rawPassword);

    /**
     * Whether the password matches. Takes constant time with respect to the
     * password, and takes the same time whether or not a user exists —
     * see {@code SignInService} once there is one.
     */
    boolean matches(String rawPassword, String encodedHash);

    /**
     * Whether {@code encodedHash} was produced with weaker parameters than the
     * ones now configured. Sign-in rehashes when it is: the password is in hand
     * at exactly that moment and never again.
     */
    boolean needsRehash(String encodedHash);

    PasswordAlgorithm algorithm();
}
