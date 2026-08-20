package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.pledge.application.BackerFilter;
import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database refuses about a saved segment.
 *
 * <p>The one that carries the design is {@link #theStoredStatesAreTheStatesTheReportCovers()}.
 * V31's header promises that its check constraint is held against
 * {@link BackerFilter#REPORTED} rather than against a copy of it, and this is that promise:
 * the constraint and the enum disagreeing would mean either a segment nobody can save or a
 * segment naming a state the report cannot answer, and neither has a symptom until a
 * creator tries it.
 *
 * <p>Every rule here is also enforced in Java, which is not duplication for
 * {@code PledgeSchemaTests}' reason: an application check is enforced by whichever code
 * path remembered to call it, and a constraint is enforced against a migration, a support
 * query and a bug.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint aborts
 * the surrounding transaction, so each of these needs its own.
 */
class BackerSegmentSchemaTests extends AbstractIntegrationTest {

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
    void clear() {
        jdbc().update("DELETE FROM backer_segments");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
        jdbc().update("DELETE FROM users WHERE email LIKE 'segment-schema%'");
    }

    @Test
    @DisplayName("the states a segment may store are the states the report covers")
    void theStoredStatesAreTheStatesTheReportCovers() {
        // Read out of the constraint rather than asserted against a literal list, so that
        // this fails when either side moves and not only when the migration does.
        String constraint = jdbc().queryForObject(
                """
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                 WHERE t.relname = 'backer_segments' AND c.conname = 'backer_segments_states_known'
                """,
                String.class);

        assertThat(constraint).isNotNull();
        for (var state : BackerFilter.REPORTED) {
            assertThat(constraint).contains("'" + state.name() + "'");
        }
        // And the constraint names no more than the enum does: a sixth state in the
        // column would be one the report has no query for. Counted from the quotes, which
        // is the one thing about PostgreSQL's rendering of a constraint that does not vary.
        long quoted = constraint.chars().filter(character -> character == '\'').count() / 2;
        assertThat(quoted).isEqualTo(BackerFilter.REPORTED.size());
    }

    @Test
    @DisplayName("a state the report does not cover is refused")
    void anUnreportedStateIsRefused() {
        UUID project = campaign();

        assertThatThrownBy(() -> insert(project, "Expired", "ARRAY['EXPIRED']::text[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an empty array is refused, because absent already means \"any\"")
    void anEmptyArrayIsRefused() {
        UUID project = campaign();

        // Two spellings of one fact is how the two come to disagree: V31 stores "this axis
        // does not narrow anything" as NULL, and an empty array would say it a second way.
        assertThatThrownBy(() -> insert(project, "Nothing", "ARRAY[]::text[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a destination that is not a country code is refused")
    void aMalformedCountryIsRefused() {
        UUID project = campaign();

        assertThatThrownBy(() -> jdbc().update(
                        """
                        INSERT INTO backer_segments (id, project_id, name, countries, created_by)
                        VALUES (?, ?, 'Germany', ARRAY['Germany']::text[], (SELECT creator_id FROM projects WHERE id = ?))
                        """,
                        Identifiers.newIdentifier(),
                        project,
                        project))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the check is element-wise rather than a length: a member of the right shape
        // beside one of the wrong shape must still fail.
        assertThatThrownBy(() -> jdbc().update(
                        """
                        INSERT INTO backer_segments (id, project_id, name, countries, created_by)
                        VALUES (?, ?, 'Mixed', ARRAY['DE', 'DEU']::text[], (SELECT creator_id FROM projects WHERE id = ?))
                        """,
                        Identifiers.newIdentifier(),
                        project,
                        project))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two segments on one campaign cannot share a name, compared folded and trimmed")
    void namesAreUniquePerCampaignAndFolded() {
        UUID project = campaign();
        assertThatCode(() -> insert(project, "Germany", "NULL")).doesNotThrowAnyException();

        assertThatThrownBy(() -> insert(project, "  germany ", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A different campaign is a different namespace: two creators may both have a
        // segment called "Germany".
        UUID other = campaign();
        assertThatCode(() -> insert(other, "Germany", "NULL")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a segment does not outlive the campaign it filters")
    void aSegmentDoesNotOutliveItsCampaign() {
        UUID project = campaign();
        insert(project, "Germany", "NULL");

        jdbc().update("DELETE FROM project_state_transitions WHERE project_id = ?", project);
        jdbc().update("DELETE FROM projects WHERE id = ?", project);

        // There is nothing left for the filter to be evaluated against, and a saved filter
        // pointing at nothing is a row that only ever produces an error message.
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM backer_segments WHERE project_id = ?", Integer.class, project))
                .isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID campaign() {
        int sequence = SEQUENCE.incrementAndGet();
        UUID creator = Campaigns.creator(dataSource, "segment-schema" + sequence);
        return Campaigns.seed(dataSource, creator, "segment-schema-" + sequence)
                .state("LIVE")
                .insert();
    }

    private void insert(UUID project, String name, String statesExpression) {
        jdbc().update(
                        """
                        INSERT INTO backer_segments (id, project_id, name, states, created_by)
                        VALUES (?, ?, ?, %s, (SELECT creator_id FROM projects WHERE id = ?))
                        """
                                .formatted(statesExpression),
                        Identifiers.newIdentifier(),
                        project,
                        name,
                        project);
    }
}
