package az.ideanest.auth.domain;

/**
 * How a stored password hash was produced.
 *
 * <p>Argon2's encoded hash already carries its parameters, so this is
 * redundant for verification. It is not redundant for the question that gets
 * asked during a migration — "how many users are still on the old scheme?" —
 * which should be an indexed count, not a scan that parses strings.
 */
public enum PasswordAlgorithm {

    /**
     * Argon2id: memory-hard, which is what makes a GPU no more useful than a
     * CPU against a stolen table.
     */
    ARGON2ID
}
