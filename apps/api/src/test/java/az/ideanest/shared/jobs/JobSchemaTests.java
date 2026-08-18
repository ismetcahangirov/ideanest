package az.ideanest.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V20 refuses about a scheduler row.
 *
 * <p>Every rule here is also enforced in Java, and that is not duplication for the
 * reason {@code OutboxSchemaTests} gives: an application check is enforced by
 * whichever code path remembered to call it, and a constraint is enforced against a
 * migration, a support query, and an operator resetting a job at three in the
 * morning — which is exactly when a half-written row is written.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own, which a
 * {@link JdbcTemplate} against an auto-committing connection gives.
 */
class JobSchemaTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

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
    void removeTestJobs() {
        jdbc().update("DELETE FROM scheduled_jobs WHERE name LIKE 'test-%'");
    }

    /**
     * One row, spelled out. Every column the table has, so that a test which changes
     * one of them is changing exactly one thing.
     */
    private int insert(String name, String state, int attempts, String holder, Instant lockExpiresAt, String error) {
        return jdbc().update(
                        """
                        INSERT INTO scheduled_jobs (
                            name, state, lock_holder, lock_expires_at, last_run_at,
                            last_error, attempts, next_attempt_at)
                        VALUES (?, ?, ?, ?, now(), ?, ?, now())
                        """,
                        name,
                        state,
                        holder,
                        lockExpiresAt == null ? null : Timestamp.from(lockExpiresAt),
                        error,
                        attempts);
    }

    private String aName() {
        return "test-schema-" + SEQUENCE.incrementAndGet();
    }

    @Test
    @DisplayName("V20 applied, and an unclaimed job is what a registered one looks like")
    void theMigrationApplied() {
        Integer applied = jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '20' AND success = true", Integer.class);
        assertThat(applied).isEqualTo(1);

        assertThatCode(() -> insert(aName(), "READY", 0, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a job name is claimed by exactly one row")
    void theNameIsTheIdentity() {
        String name = aName();
        insert(name, "READY", 0, null, null, null);

        // The lock is taken on the name. Two rows for one job would be two schedulers
        // each believing they hold it, and the claim would succeed for both.
        assertThatThrownBy(() -> insert(name, "READY", 0, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a state outside the two the runner knows is refused")
    void onlyTwoStatesExist() {
        // READY and DEAD. RUNNING is deliberately absent: a process killed mid-run
        // would leave it behind for ever, and a word has no expiry. The lease says
        // who holds the job.
        assertThatThrownBy(() -> insert(aName(), "RUNNING", 0, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("half a lease is not a lease")
    void aLeaseHasBothHalves() {
        // A holder with no expiry never expires — the job stops for ever the first
        // time a replica is killed. An expiry with no holder names nobody, so a
        // release cannot be refused to the wrong caller.
        assertThatThrownBy(() -> insert(aName(), "READY", 0, "replica-a", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(aName(), "READY", 0, null, Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(aName(), "READY", 0, "replica-a", Instant.now(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a job that gave up says why")
    void aDeadJobSaysWhy() {
        // The whole value of the terminal state is that somebody can find out what
        // happened. A DEAD row with no error is work that silently stopped, and
        // nothing will ever retry it to find out what it would say.
        assertThatThrownBy(() -> insert(aName(), "DEAD", 8, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(aName(), "DEAD", 0, null, null, "the database is down"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(aName(), "DEAD", 8, null, null, "the database is down"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("attempts only ever counts upwards from nothing")
    void attemptsIsNotNegative() {
        assertThatThrownBy(() -> insert(aName(), "READY", -1, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a job with no name is not a job")
    void theNameHasContent() {
        assertThatThrownBy(() -> insert("   ", "READY", 0, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
