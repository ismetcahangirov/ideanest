package az.ideanest.project;

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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database refuses about a launch reminder.
 *
 * <p>Every rule here is also enforced in Java, for the reason
 * {@link CollaboratorSchemaTests} gives: an application check is enforced by
 * whichever code path remembered to call it, and a constraint is enforced against
 * a migration, a support query, a bulk import, and a bug.
 *
 * <p>These rows decide who receives mail with our name on the envelope, so the
 * incoherent ones are worth refusing where they would be written: a reminder for
 * nobody, a reminder for two people at once, a second row for somebody who is
 * already on the list, and a notice that was sent with no way to stop the next
 * one.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a
 * constraint aborts the surrounding transaction, so each of these needs its own.
 */
class ReminderSchemaTests extends AbstractIntegrationTest {

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
        // Reminders cascade from projects, which this also exercises. Users are
        // left for the identity tests' cleanup.
        jdbc().update("DELETE FROM reminders");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
    }

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "reminder-schema-" + SEQUENCE.incrementAndGet();
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

    private UUID insertReminder(UUID projectId, UUID userId, String email) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO reminders (id, project_id, user_id, email)"
                                + " VALUES (?, ?, ?, CAST(? AS citext))",
                        id,
                        projectId,
                        userId,
                        email);
        return id;
    }

    private static byte[] hash(int seed) {
        byte[] hash = new byte[32];
        hash[0] = (byte) seed;
        hash[1] = (byte) (seed >> 8);
        return hash;
    }

    // -----------------------------------------------------------------------
    // One identity per row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a reminder names an account or an address, never both and never neither")
    void aReminderHasExactlyOneIdentity() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);

        assertThatCode(() -> insertReminder(projectId, creator, null)).doesNotThrowAnyException();
        assertThatCode(() -> insertReminder(projectId, null, "stranger@example.com"))
                .doesNotThrowAnyException();

        // A reminder for nobody can never be delivered, and one for an account and
        // an address at once is one person counted twice.
        assertThatThrownBy(() -> insertReminder(projectId, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(projectId, creator, "someone-else@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an address that is not an address is refused")
    void theAddressHasToLookLikeOne() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);

        // The same loose shape as users.email_shape. The real test of an address is
        // whether the message arrives, so this catches the values that could never
        // be attempted rather than trying to be RFC 5322.
        assertThatThrownBy(() -> insertReminder(projectId, null, "not-an-address"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(projectId, null, "a@b"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Idempotency, in the database rather than in the service
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one account is one reminder per campaign")
    void anAccountCannotBeOnTheListTwice() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        insertReminder(projectId, creator, null);

        // This is the check the endpoint relies on. Without it, two clicks arriving
        // together would both read no row, both insert, and the campaign would owe
        // that person two emails.
        assertThatThrownBy(() -> insertReminder(projectId, creator, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A second campaign is a second reminder, which is the point of the
        // composite key.
        UUID other = insertProject(creator);
        assertThatCode(() -> insertReminder(other, creator, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one address is one reminder per campaign, whatever case it is written in")
    void anAddressCannotBeOnTheListTwice() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        insertReminder(projectId, null, "Stranger@Example.com");

        // citext, so the unique index sees one address rather than two. Without it,
        // the same person is on the list twice by capitalising their own name.
        assertThatThrownBy(() -> insertReminder(projectId, null, "stranger@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two anonymous reminders on one campaign are not a conflict")
    void twoDifferentAddressesAreTwoReminders() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);

        // The unique indexes are partial for a reason: user_id is null on every one
        // of these rows, and a plain unique index on (project_id, user_id) would
        // have looked like a constraint while permitting anything.
        assertThatCode(() -> {
                    insertReminder(projectId, null, "one@example.com");
                    insertReminder(projectId, null, "two@example.com");
                    insertReminder(projectId, null, "three@example.com");
                })
                .doesNotThrowAnyException();

        assertThat(jdbc().queryForObject(
                                "SELECT count(*) FROM reminders WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Delivery state
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a notice that was sent carries the way out of the next one")
    void aNotifiedReminderHasAnUnsubscribeToken() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID reminderId = insertReminder(projectId, null, "stranger@example.com");

        // Stamping the row without a token is the mail people report: they were
        // told, and there is nothing in the message that stops the next one.
        assertThatThrownBy(() -> jdbc().update("UPDATE reminders SET notified_at = now() WHERE id = ?", reminderId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the other way round: a live unsubscribe link for a message nobody
        // ever sent.
        assertThatThrownBy(() -> jdbc().update(
                                "UPDATE reminders SET unsubscribe_token_hash = ? WHERE id = ?", hash(1), reminderId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                                "UPDATE reminders SET notified_at = now(), unsubscribe_token_hash = ? WHERE id = ?",
                                hash(2),
                                reminderId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unsubscribe token has to be a SHA-256")
    void theTokenHashIsSha256() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID reminderId = insertReminder(projectId, null, "stranger@example.com");

        // Anything shorter is a caller that hashed with something else — or did not
        // hash at all, which would put the working link in the table.
        assertThatThrownBy(() -> jdbc().update(
                                "UPDATE reminders SET notified_at = now(), unsubscribe_token_hash = ? WHERE id = ?",
                                new byte[16],
                                reminderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("one unsubscribe token is one reminder")
    void tokensAreUnique() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID first = insertReminder(projectId, null, "one@example.com");
        UUID second = insertReminder(projectId, null, "two@example.com");

        jdbc().update(
                        "UPDATE reminders SET notified_at = now(), unsubscribe_token_hash = ? WHERE id = ?",
                        hash(7),
                        first);

        // A link that identified two rows would unsubscribe somebody who did not
        // click it.
        assertThatThrownBy(() -> jdbc().update(
                                "UPDATE reminders SET notified_at = now(), unsubscribe_token_hash = ? WHERE id = ?",
                                hash(7),
                                second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Lifetime
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deleting a campaign takes its reminders with it")
    void remindersCascadeFromTheCampaign() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        insertReminder(projectId, null, "stranger@example.com");

        // A hard-deleted campaign is one that never launched, and a reminder about
        // a campaign that does not exist can never be delivered.
        jdbc().update("DELETE FROM projects WHERE id = ?", projectId);

        assertThat(jdbc().queryForObject(
                                "SELECT count(*) FROM reminders WHERE project_id = ?", Long.class, projectId))
                .isZero();
    }

    @Test
    @DisplayName("a reminder cannot name an account that does not exist")
    void theAccountHasToExist() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);

        assertThatThrownBy(() -> insertReminder(projectId, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
