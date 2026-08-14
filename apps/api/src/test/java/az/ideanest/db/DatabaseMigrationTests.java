package az.ideanest.db;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proof that migrations actually ran, against a real PostgreSQL.
 *
 * <p>The convention tests read the files. These run them.
 */
class DatabaseMigrationTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("migrations applied at start-up and the history is clean")
    void migrationsApplied() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isNotNull().isPositive();

        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        // A failed row means a migration ran halfway. PostgreSQL rolls back
        // transactional DDL, but the row stays and Flyway then refuses to move,
        // which is the correct behaviour and worth knowing about here.
        assertThat(failed).isZero();
    }

    @Test
    @DisplayName("the applied checksums still match the files on disk")
    void checksumsValidate() {
        // Editing a migration that has already run is the mistake this catches.
        // It applies cleanly to a fresh database and silently leaves every
        // existing environment on the old definition.
        flyway.validate();
    }

    @Test
    @DisplayName("the extensions the schema depends on are installed")
    void extensionsInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> installed = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);

        assertThat(installed).contains("citext", "pgcrypto", "pg_trgm");
    }

    @Test
    @DisplayName("citext compares case-insensitively, as email handling depends on")
    void citextBehavesAsExpected() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The extension being installed is not the same as it working. Two
        // accounts for one person is the failure this prevents, and it is
        // cheaper to assert than to migrate away from later.
        Boolean equal = jdbc.queryForObject(
                "SELECT 'Person@Example.COM'::citext = 'person@example.com'::citext", Boolean.class);
        assertThat(equal).isTrue();
    }
}
