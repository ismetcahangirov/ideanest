package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * What the database refuses about an update.
 *
 * <p>Every rule here is also enforced in Java. That is not duplication, for the reason
 * {@code ProjectSchemaTests} gives: an application check is enforced by whichever code
 * path remembered to call it, and a constraint is enforced against a migration, a
 * support query, a bulk import, and a bug. The one that matters most is the unique
 * number, because it is not merely a guard — it is what decides a race between two
 * writers publishing a campaign's first update at the same instant.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own. A
 * {@link JdbcTemplate} against an auto-committing connection gives exactly that.
 */
class ProjectUpdateSchemaTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts these tests create.
     *
     * <p>A counter rather than a slice of the identifier, for {@code ProjectSchemaTests}'
     * reason: UUID version 7 begins with a millisecond timestamp, so two accounts created
     * in the same millisecond share their leading digits.
     */
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
    void clearProjects() {
        // Updates cascade from projects, which this also exercises. The users are
        // left for the identity tests' own cleanup; projects reference them and
        // deliberately do not cascade.
        jdbc().update("DELETE FROM project_updates");
        jdbc().update("DELETE FROM projects");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "update-schema-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Creator",
                        marker);
        return id;
    }

    private UUID insertProject(UUID creatorId) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, ?, ?)",
                        id,
                        creatorId,
                        "campaign-" + SEQUENCE.incrementAndGet(),
                        "A campaign");
        return id;
    }

    private int insertUpdate(UUID projectId, UUID authorId, int number) {
        return insertUpdate(projectId, authorId, number, "An update", "Something happened.", "PUBLIC", Instant.now());
    }

    private int insertUpdate(
            UUID projectId, UUID authorId, int number, String title, String body, String visibility, Instant at) {

        return jdbc().update(
                        """
                        INSERT INTO project_updates
                            (id, project_id, number, title, body, visibility, author_id, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        Identifiers.newIdentifier(),
                        projectId,
                        number,
                        title,
                        body,
                        visibility,
                        authorId,
                        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    // -----------------------------------------------------------------------
    // The number
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two updates cannot claim the same number on one campaign")
    void numbersAreUniquePerCampaign() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        insertUpdate(project, author, 1);

        // This is the constraint that decides the race ProjectUpdateService cannot
        // lock its way out of: a campaign's first update has no earlier row to lock
        // behind, so two writers can both compute 1 and one of them has to lose.
        assertThatThrownBy(() -> insertUpdate(project, author, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two campaigns may each have an update numbered 1")
    void numbersAreScopedToTheCampaign() {
        UUID author = insertUser();
        UUID first = insertProject(author);
        UUID second = insertProject(author);
        insertUpdate(first, author, 1);

        // "Update 1" means "the first update of this campaign", not a global
        // sequence. A globally unique number would leak how many updates the
        // platform has published, and would make the number useless to a reader.
        assertThatCode(() -> insertUpdate(second, author, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("updates are numbered from one")
    void numbersStartAtOne() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        assertThatThrownBy(() -> insertUpdate(project, author, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a title of whitespace is refused")
    void titlesAreNotBlank() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        // NOT NULL would accept this. The Updates tab would then list a row with
        // nothing to click on.
        assertThatThrownBy(
                        () -> insertUpdate(project, author, 1, "   ", "Something happened.", "PUBLIC", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a body of whitespace is refused")
    void bodiesAreNotBlank() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        assertThatThrownBy(() -> insertUpdate(project, author, 1, "An update", "\n\n", "PUBLIC", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unknown visibility is refused")
    void visibilityIsAClosedSet() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        // A text column with a check rather than a native enum — see V22 — so this
        // is the thing that keeps the column from accumulating spellings.
        assertThatThrownBy(() -> insertUpdate(
                        project, author, 1, "An update", "Something happened.", "EVERYONE", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insertUpdate(
                        project, author, 2, "An update", "Something happened.", "BACKERS_ONLY", Instant.now()))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Scheduling and lifetime
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("published_at may be in the future, which is the whole of scheduling")
    void publicationMayBeScheduled() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        // There is no state column and no job. If the database refused a future
        // timestamp here, CD-12's scheduling would need both.
        assertThatCode(() -> insertUpdate(
                        project,
                        author,
                        1,
                        "Next week",
                        "Something will happen.",
                        "PUBLIC",
                        Instant.now().plusSeconds(604_800)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("deleting a campaign takes its updates with it")
    void updatesCascadeFromTheCampaign() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        insertUpdate(project, author, 1);

        jdbc().update("DELETE FROM projects WHERE id = ?", project);

        // A campaign that can still be hard-deleted is one that never launched, so
        // nobody read its updates and they mean nothing without it.
        Integer left = jdbc().queryForObject(
                        "SELECT count(*) FROM project_updates WHERE project_id = ?", Integer.class, project);
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("an update cannot name an author who does not exist")
    void authorsAreReal() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        assertThatThrownBy(() -> insertUpdate(project, Identifiers.newIdentifier(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
