package az.ideanest.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V23 refuses about a report.
 *
 * <p>Every rule here is also enforced in Java, and that is deliberate rather than
 * redundant, for the reason {@code ReminderSchemaTests} gives: an application check
 * is enforced by whichever code path remembered to call it, and a constraint is
 * enforced against a migration, a support query, a bulk import, and a bug.
 *
 * <p><strong>The test that carries the issue is
 * {@link #oneReporterCannotPileUpOpenReportsOnOneTarget()}.</strong> "Duplicate
 * reports on the same target by the same reporter must not multiply" is the one
 * requirement here that a service-layer check cannot keep: two taps on a slow
 * connection both read no row and both insert, and only the database can break that
 * tie.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own, which a
 * {@link JdbcTemplate} against an auto-committing connection gives.
 */
class ContentReportSchemaTests extends AbstractIntegrationTest {

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
    void clearReports() {
        // Reports deliberately do not cascade from anything -- that is the point of
        // the table -- so this suite has to remove its own rows. Users are left for
        // the identity tests' cleanup.
        jdbc().update("DELETE FROM content_reports");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
    }

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "report-schema-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Person",
                        marker);
        return id;
    }

    private UUID insertProject(UUID creatorId) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, ?, ?)",
                        id,
                        creatorId,
                        "a-campaign-" + SEQUENCE.incrementAndGet(),
                        "A campaign");
        return id;
    }

    /** One report, spelled out, so that a test which varies one column varies exactly one thing. */
    private UUID insertReport(String targetType, UUID targetId, UUID reporterId, String reason, String detail) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        """
                        INSERT INTO content_reports (id, target_type, target_id, reporter_id, reason, detail)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        targetType,
                        targetId,
                        reporterId,
                        reason,
                        detail);
        return id;
    }

    private int resolve(UUID reportId, String state, UUID moderatorId, String note) {
        return jdbc().update(
                        """
                        UPDATE content_reports
                           SET state = ?, resolved_by = ?, resolved_at = now(), resolution_note = ?
                         WHERE id = ?
                        """,
                        state,
                        moderatorId,
                        note,
                        reportId);
    }

    // -----------------------------------------------------------------------
    // Duplicate suppression
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one reporter cannot pile up two open reports on one target")
    void oneReporterCannotPileUpOpenReportsOnOneTarget() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());

        insertReport("PROJECT", project, reporter, "FRAUD", null);

        // The only triage signal this queue has is how many people reported a
        // thing. A reporter who can add to that number at will chooses it.
        assertThatThrownBy(() -> insertReport("PROJECT", project, reporter, "SPAM", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("two reporters may each report the same target once")
    void twoReportersMayReportTheSameTarget() {
        UUID project = insertProject(insertUser());

        insertReport("PROJECT", project, insertUser(), "FRAUD", null);

        // The count is the signal. Suppressing the second person's report would
        // make a campaign fifty people reported look like one nobody did.
        assertThatCode(() -> insertReport("PROJECT", project, insertUser(), "FRAUD", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reporter may report again once their earlier report is resolved")
    void resolvingAReportReleasesTheReporterToReportAgain() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());
        UUID moderator = insertUser();

        UUID first = insertReport("PROJECT", project, reporter, "SPAM", null);
        resolve(first, "DISMISSED", moderator, "Not spam.");

        // The index is partial on OPEN precisely so this works. Somebody told in
        // March that their report was dismissed, finding the same campaign doing
        // something worse in June, is making a new complaint about new facts --
        // and an absolute unique index would drop it while showing them a success.
        assertThatCode(() -> insertReport("PROJECT", project, reporter, "FRAUD", "It got worse."))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // The closed vocabularies
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a target type outside the four is refused")
    void targetTypeIsClosed() {
        UUID reporter = insertUser();

        assertThatThrownBy(() -> insertReport("PLEDGE", UUID.randomUUID(), reporter, "FRAUD", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the two surfaces the community module has not built yet are already accepted")
    void commentAndUpdateTargetsAreAlreadyEnumerated() {
        UUID reporter = insertUser();

        // Nothing writes these yet -- neither table exists -- and the constraint
        // accepts them so that shipping §4.9 is not also a migration on somebody
        // else's critical path.
        assertThatCode(() -> insertReport("COMMENT", UUID.randomUUID(), reporter, "SPAM", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertReport("PROJECT_UPDATE", UUID.randomUUID(), reporter, "SPAM", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reason outside the taxonomy is refused")
    void reasonIsClosed() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());

        assertThatThrownBy(() -> insertReport("PROJECT", project, reporter, "I_DO_NOT_LIKE_IT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a state outside the three is refused")
    void stateIsClosed() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());
        UUID report = insertReport("PROJECT", project, reporter, "FRAUD", null);

        assertThatThrownBy(() -> resolve(report, "ESCALATED", insertUser(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // A report that cannot be acted on
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("OTHER has to say what")
    void otherRequiresDetail() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());

        assertThatThrownBy(() -> insertReport("PROJECT", project, reporter, "OTHER", "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertReport("PROJECT", project, reporter, "OTHER", "It is selling a raffle ticket."))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nobody reports themselves")
    void aReporterIsNotTheirOwnTarget() {
        UUID reporter = insertUser();

        assertThatThrownBy(() -> insertReport("USER", reporter, reporter, "SPAM", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("detail is bounded, because nothing prunes this table")
    void detailIsBounded() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());

        assertThatThrownBy(() -> insertReport("PROJECT", project, reporter, "OTHER", "x".repeat(2001)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // A resolution arrives whole
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a resolved report names the moderator who resolved it and when")
    void aResolutionArrivesWhole() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());
        UUID report = insertReport("PROJECT", project, reporter, "FRAUD", null);

        // A decision with no decider is a decision nobody can be asked about.
        assertThatThrownBy(() -> jdbc().update(
                                "UPDATE content_reports SET state = 'UPHELD' WHERE id = ?", report))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> resolve(report, "UPHELD", insertUser(), "Prohibited item."))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an open report carries no resolution, not even a note")
    void anOpenReportIsUnsigned() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());
        UUID report = insertReport("PROJECT", project, reporter, "FRAUD", null);

        // Otherwise "already looked at" and "still waiting" are the same row, and
        // the next moderator triages it a second time.
        assertThatThrownBy(() -> jdbc().update(
                                "UPDATE content_reports SET resolution_note = 'seen' WHERE id = ?", report))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a report starts open")
    void aReportStartsOpen() {
        UUID reporter = insertUser();
        UUID project = insertProject(insertUser());
        UUID report = insertReport("PROJECT", project, reporter, "FRAUD", null);

        String state = jdbc().queryForObject("SELECT state FROM content_reports WHERE id = ?", String.class, report);
        assertThat(state).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a report survives the campaign it was about")
    void aReportOutlivesItsTarget() {
        UUID creator = insertUser();
        UUID project = insertProject(creator);
        UUID report = insertReport("PROJECT", project, insertUser(), "FRAUD", null);

        jdbc().update("DELETE FROM project_state_transitions WHERE project_id = ?", project);
        jdbc().update("DELETE FROM projects WHERE id = ?", project);

        // No foreign key on the target, deliberately: a campaign hard deleted
        // during an investigation must not take the complaint with it.
        Integer surviving =
                jdbc().queryForObject("SELECT count(*) FROM content_reports WHERE id = ?", Integer.class, report);
        assertThat(surviving).isEqualTo(1);
    }
}
