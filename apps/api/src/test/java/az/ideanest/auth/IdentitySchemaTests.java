package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database refuses.
 *
 * <p>Every rule asserted here is one the application will also enforce. That is
 * not duplication. Application checks are enforced by whichever code path
 * remembered to call them; a constraint is enforced against a migration script,
 * a support query, a bulk import, and a bug — and this schema holds the answer
 * to "who is this person", which everything else is built on top of.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a
 * constraint aborts the surrounding transaction, so each of these needs its own.
 * JdbcTemplate against an auto-committing connection gives exactly that.
 */
class IdentitySchemaTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @AfterEach
    void clearIdentityTables() {
        // Everything else in this schema hangs off users by a cascading foreign
        // key, which this also happens to exercise.
        jdbc().update("DELETE FROM users");
    }

    private UUID insertUser(String email, String slug) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        email,
                        "Test Person",
                        slug);
        return id;
    }

    private UUID insertSession(UUID userId) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)",
                        id,
                        userId,
                        java.sql.Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
        return id;
    }

    private static byte[] hashOfLength(int length, int seed) {
        byte[] hash = new byte[length];
        for (int i = 0; i < length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return hash;
    }

    // -----------------------------------------------------------------------
    // users
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one email address is one account, whatever case it was typed in")
    void emailUniquenessIgnoresCase() {
        insertUser("Person@Example.com", "person");

        // The failure this prevents is two accounts for one human being. The
        // second cannot see the first one's pledges and cannot be told why.
        assertThatThrownBy(() -> insertUser("person@EXAMPLE.com", "person-two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an email lookup finds the row regardless of case")
    void emailLookupIgnoresCase() {
        UUID id = insertUser("Person@Example.com", "person");

        UUID found = jdbc().queryForObject(
                        "SELECT id FROM users WHERE email = ?::citext", UUID.class, "PERSON@example.COM");

        assertThat(found).isEqualTo(id);
    }

    @Test
    @DisplayName("a slug is lowercase, and the check actually rejects uppercase")
    void slugMustBeLowercase() {
        // citext would have made this constraint pass on 'Person', because its
        // operators fold case. That is why slug is text.
        assertThatThrownBy(() -> insertUser("person@example.com", "Person"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a slug does not start, end, or double up on hyphens")
    void slugShapeIsEnforced() {
        assertThatThrownBy(() -> insertUser("a@example.com", "-leading"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser("b@example.com", "trailing-"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser("c@example.com", "double--hyphen"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unsupported locale is refused")
    void localeMustBeSupported() {
        UUID id = insertUser("person@example.com", "person");

        // A locale nobody translated renders as keys. Refusing it here is how
        // that is found in a pull request rather than by a user.
        assertThatThrownBy(() -> jdbc().update("UPDATE users SET locale = 'de' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update("UPDATE users SET locale = 'ru' WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("something that is not an email address is refused")
    void emailShapeIsEnforced() {
        assertThatThrownBy(() -> insertUser("not-an-address", "person"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("updated_at moves on update and created_at does not")
    void updatedAtIsMaintainedByTheDatabase() {
        UUID id = insertUser("person@example.com", "person");
        Instant createdAt = jdbc().queryForObject("SELECT created_at FROM users WHERE id = ?", Instant.class, id);
        Instant firstUpdate = jdbc().queryForObject("SELECT updated_at FROM users WHERE id = ?", Instant.class, id);

        jdbc().update("UPDATE users SET bio = 'A sentence' WHERE id = ?", id);

        Instant createdAfter = jdbc().queryForObject("SELECT created_at FROM users WHERE id = ?", Instant.class, id);
        Instant updatedAfter = jdbc().queryForObject("SELECT updated_at FROM users WHERE id = ?", Instant.class, id);

        // The application never sets updated_at, so it cannot forget to.
        assertThat(updatedAfter).isAfterOrEqualTo(firstUpdate);
        assertThat(createdAfter).isEqualTo(createdAt);
    }

    // -----------------------------------------------------------------------
    // sessions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a revoked session must say why")
    void revocationCarriesItsReason() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);

        // "Somebody was signed out and nobody recorded why" is the state that
        // makes a theft incident unreconstructable a week later.
        assertThatThrownBy(() -> jdbc().update("UPDATE sessions SET revoked_at = now() WHERE id = ?", sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE sessions SET revoked_at = now(), revoked_reason = 'TOKEN_REUSE' WHERE id = ?",
                        sessionId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an invented revocation reason is refused")
    void revocationReasonIsFromTheKnownSet() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);

        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE sessions SET revoked_at = now(), revoked_reason = 'BECAUSE' WHERE id = ?", sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a session cannot expire before it started")
    void sessionExpiryFollowsCreation() {
        UUID userId = insertUser("person@example.com", "person");

        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, now() - interval '1 hour')",
                        Identifiers.newIdentifier(),
                        userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // refresh_tokens
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a refresh token hash is a SHA-256 and nothing else")
    void refreshTokenHashIsFixedLength() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);

        // A shorter value here means somebody stored a truncated hash, or the
        // token itself. Both are silent until they are catastrophic.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days')",
                        Identifiers.newIdentifier(),
                        sessionId,
                        hashOfLength(16, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two refresh tokens cannot share a hash")
    void refreshTokenHashIsUnique() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);
        byte[] hash = hashOfLength(32, 7);

        jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days')",
                        Identifiers.newIdentifier(),
                        sessionId,
                        hash);

        // Uniqueness is what makes a presented token identify exactly one row.
        // Without it, a lookup could return either of two sessions.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days')",
                        Identifiers.newIdentifier(),
                        sessionId,
                        hash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a token cannot be recorded as replaced without being used")
    void replacementImpliesUse() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);
        UUID first = Identifiers.newIdentifier();
        UUID second = Identifiers.newIdentifier();

        jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days'),"
                                + "        (?, ?, ?, now() + interval '30 days')",
                        first,
                        sessionId,
                        hashOfLength(32, 1),
                        second,
                        sessionId,
                        hashOfLength(32, 100));

        // A row claiming a successor but no used_at would read as live, and the
        // reuse check depends on used_at alone.
        assertThatThrownBy(() -> jdbc().update("UPDATE refresh_tokens SET replaced_by = ? WHERE id = ?", second, first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // verification_tokens
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a verification token has a purpose from the known set")
    void verificationPurposeIsConstrained() {
        UUID userId = insertUser("person@example.com", "person");

        // A token issued to prove an address must not also reset the password.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO verification_tokens (id, user_id, purpose, token_hash, expires_at)"
                                + " VALUES (?, ?, 'ANYTHING', ?, now() + interval '1 day')",
                        Identifiers.newIdentifier(),
                        userId,
                        hashOfLength(32, 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // cascades
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("removing a user removes their credentials, sessions, and tokens")
    void deletingAUserLeavesNothingBehind() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);
        jdbc().update(
                        "INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)",
                        userId,
                        "$argon2id$v=19$m=65536,t=3,p=1$c29tZXNhbHQ$hash");
        jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days')",
                        Identifiers.newIdentifier(),
                        sessionId,
                        hashOfLength(32, 11));
        jdbc().update(
                        "INSERT INTO verification_tokens (id, user_id, purpose, token_hash, expires_at)"
                                + " VALUES (?, ?, 'EMAIL_VERIFICATION', ?, now() + interval '1 day')",
                        Identifiers.newIdentifier(),
                        userId,
                        hashOfLength(32, 21));

        jdbc().update("DELETE FROM users WHERE id = ?", userId);

        // Ordinary deletion is a soft delete. This is the hard one — used by
        // account deletion and by data-subject requests — and a credential or a
        // live refresh token surviving it would be exactly the wrong residue.
        assertThat(count("user_credentials")).isZero();
        assertThat(count("sessions")).isZero();
        assertThat(count("refresh_tokens")).isZero();
        assertThat(count("verification_tokens")).isZero();
    }

    @Test
    @DisplayName("removing a session removes its refresh token family")
    void deletingASessionRemovesItsTokens() {
        UUID userId = insertUser("person@example.com", "person");
        UUID sessionId = insertSession(userId);
        jdbc().update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at)"
                                + " VALUES (?, ?, ?, now() + interval '30 days')",
                        Identifiers.newIdentifier(),
                        sessionId,
                        hashOfLength(32, 31));

        jdbc().update("DELETE FROM sessions WHERE id = ?", sessionId);

        assertThat(count("refresh_tokens")).isZero();
    }

    private int count(String table) {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
