package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.support.AbstractIntegrationTest;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V19 refuses about an outbox row.
 *
 * <p>Every rule here is also enforced in Java, and that is not duplication for the
 * reason {@code PledgeSchemaTests} gives: an application check is enforced by whichever
 * code path remembered to call it, and a constraint is enforced against a migration, a
 * support query, a bulk import, and a bug. The states of this table are the ones the
 * relay decides what to do from, so a half-written one — {@code PUBLISHED} with nothing
 * to say when, {@code DEAD} with no reason — is a row nobody can act on afterwards.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own, which a
 * {@link JdbcTemplate} against an auto-committing connection gives.
 */
class OutboxSchemaTests extends AbstractIntegrationTest {

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
    void clearOutbox() {
        jdbc().update("DELETE FROM outbox_events");
    }

    /**
     * One row, spelled out. Every column the table requires, so that a test which
     * changes one of them is changing exactly one thing.
     */
    private int insert(String state, int attempts, Instant publishedAt, String lastError, String payload) {
        return jdbc().update(
                        """
                        INSERT INTO outbox_events (
                            id, aggregate_type, aggregate_id, event_type, payload,
                            state, attempts, last_error, occurred_at, next_attempt_at, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?)
                        """,
                        Identifiers.newIdentifier(),
                        "pledge",
                        Identifiers.newIdentifier(),
                        "pledge.confirmed",
                        payload,
                        state,
                        attempts,
                        lastError,
                        publishedAt == null ? null : java.sql.Timestamp.from(publishedAt));
    }

    @Test
    @DisplayName("V19 applied, and a pending row is what an enqueued event looks like")
    void theMigrationApplied() {
        // The generic proof that migrations ran lives in DatabaseMigrationTests. This
        // is the specific one: the version this feature owns, applied successfully,
        // against the database the rest of the suite runs on.
        Integer applied = jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '19' AND success = true",
                Integer.class);
        assertThat(applied).isEqualTo(1);

        assertThatCode(() -> insert("PENDING", 0, null, null, "{}")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the enqueue order is assigned by the database, not by the caller")
    void everyRowTakesItsPlaceInTheQueue() {
        insert("PENDING", 0, null, null, "{\"first\":true}");
        insert("PENDING", 0, null, null, "{\"second\":true}");

        // Ordering within an aggregate is decided by this column, so a caller must
        // never be able to choose it: two events written in one transaction share a
        // millisecond, and a UUID v7 tie is exactly the case the order matters in.
        assertThat(jdbc().queryForList("SELECT sequence_no FROM outbox_events ORDER BY sequence_no", Long.class))
                .hasSize(2)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a state outside the three the relay knows is refused")
    void onlyThreeStatesExist() {
        // PENDING, PUBLISHED, DEAD. A fourth would be a row the relay silently never
        // looks at again, which is a lost event with no symptom.
        assertThatThrownBy(() -> insert("SENT", 0, null, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("published and published_at agree, in both directions")
    void aPublishedRowSaysWhen() {
        // A PUBLISHED row with no instant cannot be audited — "was this event sent
        // before the incident" has no answer — and a PENDING row carrying one claims
        // to have been sent and is about to be sent again.
        assertThatThrownBy(() -> insert("PUBLISHED", 1, null, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("PENDING", 0, Instant.now(), null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert("PUBLISHED", 1, Instant.now(), null, "{}")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a dead letter carries the reason it died")
    void aDeadRowSaysWhy() {
        // The whole value of the terminal state is that somebody can find out what
        // happened. A DEAD row with no error is an event abandoned for reasons nobody
        // recorded, and it will never be retried to find out.
        assertThatThrownBy(() -> insert("DEAD", 8, null, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert("DEAD", 8, null, "the transport is down", "{}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("attempts only ever counts upwards from nothing")
    void attemptsIsNotNegative() {
        assertThatThrownBy(() -> insert("PENDING", -1, null, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an empty payload is not an event")
    void aPayloadHasContent() {
        // A dispatcher handed an empty body publishes a message that says nothing, and
        // the consumer cannot tell that from a serialisation bug on the way in.
        assertThatThrownBy(() -> insert("PENDING", 0, null, null, "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
