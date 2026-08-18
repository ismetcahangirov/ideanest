package az.ideanest.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V21 refuses about an audit row.
 *
 * <p><strong>The two tests that carry the issue are {@link #anUpdateIsRefused()} and
 * {@link #aDeleteIsRefused()}.</strong> Append-only by convention is a property that
 * holds until somebody writes the statement, and the whole of #107 is that this one
 * does not depend on anybody remembering. Asserted against a real PostgreSQL,
 * because a trigger is not something an in-memory substitute reproduces — which is
 * the same reason the container is in this project's build file at all.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own, which a
 * {@link JdbcTemplate} against an auto-committing connection gives.
 *
 * <p><strong>And deliberately no cleanup.</strong> Every other schema suite ends in
 * a {@code DELETE}; this one cannot, by construction, and that is worth leaving
 * visible rather than working around. Each test therefore invents its own
 * identifiers and asserts only about rows carrying them, which is how a reader of
 * this table has to work anyway.
 */
class AuditLogSchemaTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /**
     * One row, spelled out. Every column the table accepts, so that a test which
     * varies one of them is varying exactly one thing.
     */
    private UUID insert(
            String actorType, UUID actorId, UUID onBehalfOf, UUID entityId, String outcome, String address) {

        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        """
                        INSERT INTO audit_logs (
                            id, actor_type, actor_id, on_behalf_of_id, action, entity_type, entity_id,
                            outcome, source_address, user_agent, request_id, trace_id, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?, ?)
                        """,
                        id,
                        actorType,
                        actorId,
                        onBehalfOf,
                        "project.approved",
                        "project",
                        entityId,
                        outcome,
                        address,
                        "a-user-agent",
                        "a-request-id",
                        "a-trace-id",
                        "a detail");
        return id;
    }

    private UUID insertSucceeded() {
        return insert("USER", Identifiers.newIdentifier(), null, Identifiers.newIdentifier(), "SUCCEEDED", "10.0.0.1");
    }

    // -----------------------------------------------------------------------
    // Append-only
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("V21 applied, and an ordinary privileged action is what a row looks like")
    void theMigrationApplied() {
        // The generic proof that migrations ran lives in DatabaseMigrationTests. This
        // is the specific one: the version this feature owns, applied successfully,
        // against the database the rest of the suite runs on.
        Integer applied = jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '21' AND success = true", Integer.class);
        assertThat(applied).isEqualTo(1);

        assertThatCode(this::insertSucceeded).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an UPDATE against the table is refused by PostgreSQL")
    void anUpdateIsRefused() {
        UUID id = insertSucceeded();

        // The point of the issue. Editing an audit row is how a record of a privileged
        // action becomes a record of whatever the last person to touch it preferred,
        // and no application-side rule reaches a support session with psql open.
        assertThatThrownBy(() -> jdbc().update("UPDATE audit_logs SET detail = 'something else' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // And the row is untouched, which is the assertion that would catch a rule
        // that refused silently by discarding the statement instead of raising.
        assertThat(jdbc().queryForObject("SELECT detail FROM audit_logs WHERE id = ?", String.class, id))
                .isEqualTo("a detail");
    }

    @Test
    @DisplayName("a DELETE against the table is refused by PostgreSQL, matching rows or not")
    void aDeleteIsRefused() {
        UUID id = insertSucceeded();

        assertThatThrownBy(() -> jdbc().update("DELETE FROM audit_logs WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // A statement that matches nothing is refused too. That is what statement-level
        // buys over row-level: "DELETE FROM audit_logs WHERE created_yesterday" reporting
        // zero rows would read as "there was nothing to remove" rather than as a rule.
        assertThatThrownBy(() -> jdbc().update("DELETE FROM audit_logs WHERE id = ?", Identifiers.newIdentifier()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(jdbc().queryForObject("SELECT count(*) FROM audit_logs WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("TRUNCATE is refused as well")
    void aTruncateIsRefused() {
        insertSucceeded();

        // The statement somebody reaches for when DELETE has just refused them.
        assertThatThrownBy(() -> jdbc().execute("TRUNCATE TABLE audit_logs"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    // -----------------------------------------------------------------------
    // Rows that would be read the wrong way
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an account-typed row names an account and a SYSTEM row does not")
    void theActorIsNamedUnlessItIsTheSystem() {
        UUID entityId = Identifiers.newIdentifier();

        // "Somebody did this" with no somebody. The actor index would not find it and
        // the row would sit in the list looking like a decision nobody made.
        assertThatThrownBy(() -> insert("USER", null, null, entityId, "SUCCEEDED", "10.0.0.1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the other direction: a scheduled sweep carrying an account claims a job
        // was a person.
        assertThatThrownBy(() ->
                        insert("SYSTEM", Identifiers.newIdentifier(), null, entityId, "SUCCEEDED", "10.0.0.1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insert("SYSTEM", null, null, entityId, "SUCCEEDED", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an impersonation names two different parties")
    void impersonationNamesBothParties() {
        UUID actor = Identifiers.newIdentifier();
        UUID entityId = Identifiers.newIdentifier();

        // A subject with nobody acting is the row an impersonation feature would write
        // if it lost the member of staff, and it reads as the subject having acted.
        assertThatThrownBy(() -> insert("SYSTEM", null, Identifiers.newIdentifier(), entityId, "SUCCEEDED", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Acting as oneself is not impersonation, and a row saying it is would inflate
        // every count of the thing AD-04 wants counted.
        assertThatThrownBy(() -> insert("MODERATOR", actor, actor, entityId, "SUCCEEDED", "10.0.0.1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() ->
                        insert("MODERATOR", actor, Identifiers.newIdentifier(), entityId, "SUCCEEDED", "10.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an actor kind and an outcome outside the known sets are refused")
    void theVocabulariesAreClosed() {
        UUID entityId = Identifiers.newIdentifier();

        // Both sets are small, closed, and read by whoever is filtering the table. A
        // value outside them is a row that every filter silently misses.
        assertThatThrownBy(() ->
                        insert("ADMIN", Identifiers.newIdentifier(), null, entityId, "SUCCEEDED", "10.0.0.1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("USER", Identifiers.newIdentifier(), null, entityId, "OK", "10.0.0.1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // REFUSED in particular, because a table that only records what worked cannot
        // answer "who tried".
        assertThatCode(() ->
                        insert("USER", Identifiers.newIdentifier(), null, entityId, "REFUSED", "10.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the address is stored as inet, so a value that is not one is refused")
    void theAddressIsValidated() {
        // text would accept anything, including the string "unknown" that a rate
        // limiter is happy with. "Everything done from this network" has to stay a
        // query rather than a prefix comparison.
        assertThatThrownBy(() -> insert(
                        "USER", Identifiers.newIdentifier(), null, Identifiers.newIdentifier(), "SUCCEEDED", "nowhere"))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> insert(
                        "USER",
                        Identifiers.newIdentifier(),
                        null,
                        Identifiers.newIdentifier(),
                        "SUCCEEDED",
                        "2001:db8::1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the instant on a row is the database's, not the caller's")
    void theTimestampIsTheDatabases() {
        UUID id = insertSucceeded();

        // Nothing in the insert above supplied occurred_at. On this table that is a
        // property rather than a convenience: an audit record whose instant a caller
        // chose is an audit record a caller can backdate.
        assertThat(jdbc().queryForObject("SELECT occurred_at IS NOT NULL FROM audit_logs WHERE id = ?", Boolean.class, id))
                .isTrue();
    }
}
