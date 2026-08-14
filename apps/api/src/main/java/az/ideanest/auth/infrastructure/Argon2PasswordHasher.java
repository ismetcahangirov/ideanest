package az.ideanest.auth.infrastructure;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.PasswordHasher;
import az.ideanest.auth.domain.PasswordAlgorithm;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id, through Spring Security's encoder and BouncyCastle underneath.
 *
 * <p>Memory-hard by design: the cost of a guess is dominated by memory, and
 * memory is the one resource a GPU cannot multiply the way it multiplies
 * arithmetic. That is the whole reason to prefer it to bcrypt or PBKDF2 for a
 * table that will eventually be attacked offline, because tables always are.
 *
 * <p>The encoded output carries its own parameters, which is what makes
 * {@link #needsRehash(String)} possible and what makes raising the cost a
 * gradual rehash rather than a lockout.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder encoder;

    public Argon2PasswordHasher(AuthProperties properties) {
        AuthProperties.Argon2 argon2 = properties.argon2();
        this.encoder = new Argon2PasswordEncoder(
                argon2.saltLength(), argon2.hashLength(), argon2.parallelism(), argon2.memoryKib(), argon2.iterations());
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        return encoder.matches(rawPassword, encodedHash);
    }

    @Override
    public boolean needsRehash(String encodedHash) {
        return encoder.upgradeEncoding(encodedHash);
    }

    @Override
    public PasswordAlgorithm algorithm() {
        return PasswordAlgorithm.ARGON2ID;
    }
}
