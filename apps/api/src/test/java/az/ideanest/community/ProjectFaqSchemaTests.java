package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
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
 * What the database refuses about an FAQ entry.
 *
 * <p>Every rule here is also enforced in Java, by {@code FaqContent}. That is not
 * duplication, for the reason {@code ProjectUpdateSchemaTests} gives: an application
 * check is enforced by whichever code path remembered to call it, and a constraint is
 * enforced against a migration, a support query, a bulk import, and a bug. The one worth
 * reading twice is {@link #questionsAreNotBlank()} — V47 uses {@code !~ '^\s*$'} rather
 * than {@code char_length(btrim(...)) > 0} precisely because PostgreSQL's one-argument
 * {@code btrim} removes spaces and nothing else, so a question of two newlines would pass
 * the second and does not pass the first.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own. A
 * {@link JdbcTemplate} against an auto-committing connection gives exactly that.
 */
class ProjectFaqSchemaTests extends AbstractIntegrationTest {

    /**
     * What this class's fixture accounts are called.
     *
     * <p><strong>Namespaced so they cannot be another suite's.</strong> Nothing deletes
     * users between classes and {@code role + "-" + counter} is a convention several
     * suites share with counters that all start at one, so a suite that takes
     * {@code creator-1@example.com} first leaves the next one unable to register a
     * password against it — its sign-in answers 401, its next call carries
     * {@code Authorization: Bearer null}, and the failure surfaces in a fixture far from
     * the cause and only when the whole suite runs.
     */
    private static final String HANDLE_PREFIX = "faq-schema-";

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
        // FAQ entries cascade from projects, which one of these tests exercises.
        // Deleted explicitly anyway, because a cleanup that leans on a cascade stops
        // working the day the cascade is reconsidered and nothing says why. The users
        // are left where they are: projects reference them and deliberately do not
        // cascade, and `audit_logs` is never touched — it refuses DELETE, which is the
        // property it exists for.
        jdbc().update("DELETE FROM project_faqs");
        jdbc().update("DELETE FROM projects");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private String handle(String role) {
        return HANDLE_PREFIX + role + "-" + SEQUENCE.incrementAndGet();
    }

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = handle("creator");
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
                        handle("campaign"),
                        "A campaign");
        return id;
    }

    private UUID insertFaq(UUID projectId, String question, String answer, int sortOrder) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        """
                        INSERT INTO project_faqs (id, project_id, question, answer, sort_order)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        id,
                        projectId,
                        question,
                        answer,
                        sortOrder);
        return id;
    }

    private UUID insertFaq(UUID projectId, int sortOrder) {
        return insertFaq(projectId, "When do you ship?", "In March.", sortOrder);
    }

    // -----------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a question of whitespace is refused, newlines included")
    void questionsAreNotBlank() {
        UUID project = insertProject(insertUser());

        // NOT NULL would accept both of these. The second is the one the constraint is
        // written as a regexp for: btrim removes spaces only, so `char_length(btrim(...))
        // > 0` would let it through and the tab would list a row with nothing to read.
        assertThatThrownBy(() -> insertFaq(project, "   ", "In March.", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFaq(project, "\n\n", "In March.", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an answer of whitespace is refused, newlines included")
    void answersAreNotBlank() {
        UUID project = insertProject(insertUser());

        assertThatThrownBy(() -> insertFaq(project, "When do you ship?", "  ", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFaq(project, "When do you ship?", "\n\t\n", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a question stops at two hundred characters and an answer at four thousand")
    void contentIsBounded() {
        UUID project = insertProject(insertUser());

        assertThatThrownBy(() -> insertFaq(project, "q".repeat(201), "In March.", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFaq(project, "When do you ship?", "a".repeat(4001), 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Both boundaries from the other side, so this cannot pass against a table that
        // refuses everything.
        assertThatCode(() -> insertFaq(project, "q".repeat(200), "a".repeat(4000), 0))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two entries of one campaign may share a position")
    void positionsAreNotUnique() {
        UUID project = insertProject(insertUser());
        insertFaq(project, 0);

        // Deliberately not unique: a reorder rewrites every row of the campaign, and a
        // unique constraint would refuse the intermediate states of that rewrite unless
        // it were deferred. The same reasoning as reward_tiers.sort_order.
        assertThatCode(() -> insertFaq(project, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a position is not negative")
    void positionsAreNotNegative() {
        UUID project = insertProject(insertUser());

        // Positions are rewritten from zero, so a negative one is a bug rather than a
        // choice — and it would sort an entry above every other without any request
        // having said so.
        assertThatThrownBy(() -> insertFaq(project, -1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the order is per campaign, and a campaign reads back in it")
    void theOrderIsPerCampaign() {
        UUID creator = insertUser();
        UUID first = insertProject(creator);
        UUID second = insertProject(creator);

        insertFaq(first, "Second question", "Second answer", 1);
        insertFaq(first, "First question", "First answer", 0);
        insertFaq(second, "Somebody else's question", "Somebody else's answer", 0);

        List<String> questions = jdbc().queryForList(
                        "SELECT question FROM project_faqs WHERE project_id = ?"
                                + " ORDER BY sort_order ASC, created_at ASC",
                        String.class,
                        first);

        assertThat(questions).containsExactly("First question", "Second question");
    }

    // -----------------------------------------------------------------------
    // Lifetime
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deleting a campaign takes its FAQ with it")
    void faqsCascadeFromTheCampaign() {
        UUID project = insertProject(insertUser());
        insertFaq(project, 0);

        jdbc().update("DELETE FROM projects WHERE id = ?", project);

        // A campaign that can still be hard-deleted is one that never launched, so
        // nobody read its FAQ and the answers mean nothing without the campaign.
        Integer left =
                jdbc().queryForObject("SELECT count(*) FROM project_faqs WHERE project_id = ?", Integer.class, project);
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("an entry cannot name a campaign that does not exist")
    void campaignsAreReal() {
        assertThatThrownBy(() -> insertFaq(Identifiers.newIdentifier(), 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("editing an entry moves updated_at and leaves created_at alone")
    void updatedAtIsTheDatabases() {
        UUID project = insertProject(insertUser());
        UUID faq = insertFaq(project, 0);

        // The trigger, not the application: an entry must not be able to claim it was
        // edited at a time the application chose.
        jdbc().update("UPDATE project_faqs SET answer = ? WHERE id = ?", "In April.", faq);

        Boolean moved = jdbc().queryForObject(
                        "SELECT updated_at > created_at FROM project_faqs WHERE id = ?", Boolean.class, faq);
        assertThat(moved).isTrue();
    }

    // -----------------------------------------------------------------------
    // The capability this feature added
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MANAGE_FAQ is a capability the grant table accepts")
    void manageFaqIsGrantable() {
        UUID creator = insertUser();
        UUID project = insertProject(creator);
        UUID collaboratorId = Identifiers.newIdentifier();
        jdbc().update(
                        """
                        INSERT INTO collaborators
                            (id, project_id, invited_email, invitation_token_hash, invited_by,
                             created_at, expires_at)
                        VALUES (?, ?, ?::citext, ?, ?, now(), now() + interval '7 days')
                        """,
                        collaboratorId,
                        project,
                        handle("collaborator") + "@example.com",
                        new byte[32],
                        creator);

        // The half of #283 that is not the new table. Without it a creator granting this
        // capability through the People tab would be answered by a constraint violation
        // rather than by a grant, and the enum in Java would authorise something no row
        // could ever hold.
        assertThatCode(() -> jdbc().update(
                                "INSERT INTO collaborator_capabilities (collaborator_id, capability) VALUES (?, ?)",
                                collaboratorId,
                                "MANAGE_FAQ"))
                .doesNotThrowAnyException();

        // And the set is still closed. A capability nobody implemented is a grant that
        // silently authorises nothing.
        assertThatThrownBy(() -> jdbc().update(
                                "INSERT INTO collaborator_capabilities (collaborator_id, capability) VALUES (?, ?)",
                                collaboratorId,
                                "MANAGE_EVERYTHING"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc().update("DELETE FROM collaborators WHERE id = ?", collaboratorId);
    }
}
