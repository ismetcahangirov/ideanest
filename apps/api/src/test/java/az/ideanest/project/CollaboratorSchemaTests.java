package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.project.domain.Capability;
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
 * What the database refuses about a collaborator.
 *
 * <p>Every rule here is also enforced in Java, for the reason {@link ProjectSchemaTests}
 * gives: an application check is enforced by whichever code path remembered to call
 * it, and a constraint is enforced against a migration, a support query, a bulk
 * import, and a bug. These rows decide who may change a campaign that takes money,
 * so an incoherent one — accepted by nobody, revoked by nobody, holding a token
 * that is not a SHA-256 — is worth refusing at the point it would be written.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own.
 */
class CollaboratorSchemaTests extends AbstractIntegrationTest {

    /** A counter rather than a slice of the identifier; see {@link ProjectSchemaTests}. */
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
        // Capabilities cascade from collaborators and collaborators from projects,
        // which this also exercises. Users are left for the identity tests' cleanup.
        jdbc().update("DELETE FROM collaborators");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
    }

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "collab-schema-" + SEQUENCE.incrementAndGet();
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

    /** A pending invitation, with a 32-byte hash the caller can vary. */
    private UUID insertInvitation(UUID projectId, UUID invitedBy, String email, byte[] hash) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO collaborators (id, project_id, invited_email, invitation_token_hash,"
                                + " invited_by, created_at, expires_at)"
                                + " VALUES (?, ?, ?::citext, ?, ?, now(), now() + interval '7 days')",
                        id,
                        projectId,
                        email,
                        hash,
                        invitedBy);
        return id;
    }

    private static byte[] hash(int seed) {
        byte[] hash = new byte[32];
        hash[0] = (byte) seed;
        hash[1] = (byte) (seed >> 8);
        return hash;
    }

    // -----------------------------------------------------------------------
    // The token
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an invitation token hash has to be a SHA-256")
    void theTokenHashIsSha256() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);

        // Anything shorter is a caller that hashed with something else — or did not
        // hash at all, which would put the working link in the table.
        assertThatThrownBy(() -> insertInvitation(projectId, creator, "a@example.com", new byte[16]))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvitation(projectId, creator, "b@example.com", new byte[33]))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insertInvitation(projectId, creator, "c@example.com", hash(1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one token hash is one invitation")
    void tokenHashesAreUnique() {
        UUID creator = insertUser();
        UUID first = insertProject(creator);
        UUID second = insertProject(creator);
        insertInvitation(first, creator, "a@example.com", hash(2));

        // Two rows for one hash would make a presented token resolve to whichever the
        // planner found, on a lookup that decides who may edit a campaign.
        assertThatThrownBy(() -> insertInvitation(second, creator, "a@example.com", hash(2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an invitation cannot expire before it was issued")
    void expiryFollowsCreation() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(3));

        // A row like that is a link that never worked, and nothing in the application
        // would report it — the acceptance path would simply always answer "expired".
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE collaborators SET expires_at = created_at - interval '1 hour' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Coherent states
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an accepted invitation names an account and a pending one does not")
    void acceptanceNamesTheAccount() {
        UUID creator = insertUser();
        UUID invitee = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(4));

        // Accepted by nobody: a grant that counts as accepted and authorises no
        // account. The authorisation query joins on account_id, so this row would be
        // invisible to it and visible in the People tab as an active collaborator.
        assertThatThrownBy(() ->
                        jdbc().update("UPDATE collaborators SET accepted_at = now() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the other direction: an account attached to an invitation nobody has
        // accepted would authorise somebody who never clicked anything.
        assertThatThrownBy(() ->
                        jdbc().update("UPDATE collaborators SET account_id = ? WHERE id = ?", invitee, id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE collaborators SET accepted_at = now(), account_id = ? WHERE id = ?", invitee, id))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a revoked grant says who revoked it")
    void revocationIsComplete() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(5));

        // "Access was withdrawn and nobody recorded by whom" is the state that makes
        // the decision unreviewable afterwards — the same reason
        // sessions_revocation_is_complete exists.
        assertThatThrownBy(() -> jdbc().update("UPDATE collaborators SET revoked_at = now() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        jdbc().update("UPDATE collaborators SET revoked_by = ? WHERE id = ?", creator, id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE collaborators SET revoked_at = now(), revoked_by = ? WHERE id = ?", creator, id))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one live invitation per address per campaign, and a revoked one does not block")
    void oneLiveInvitationPerAddress() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID first = insertInvitation(projectId, creator, "a@example.com", hash(6));

        // Two live rows would mean revoking one leaves the other in force, silently.
        assertThatThrownBy(() -> insertInvitation(projectId, creator, "a@example.com", hash(7)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // citext: one address, whatever case it was typed in. The same decision as
        // users.email, and it has to be the same one — acceptance compares the two.
        assertThatThrownBy(() -> insertInvitation(projectId, creator, "A@Example.com", hash(8)))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc().update(
                        "UPDATE collaborators SET revoked_at = now(), revoked_by = ? WHERE id = ?", creator, first);

        // Partial index: once the first is withdrawn the same person may be invited
        // again, and the withdrawn row stays as the record that they had access.
        assertThatCode(() -> insertInvitation(projectId, creator, "a@example.com", hash(9)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one active grant per account per campaign")
    void oneActiveGrantPerAccount() {
        UUID creator = insertUser();
        UUID invitee = insertUser();
        UUID projectId = insertProject(creator);
        UUID first = insertInvitation(projectId, creator, "a@example.com", hash(10));
        UUID second = insertInvitation(projectId, creator, "b@example.com", hash(11));

        jdbc().update("UPDATE collaborators SET accepted_at = now(), account_id = ? WHERE id = ?", invitee, first);

        // Two accepted rows for one account would make "what may this person do here"
        // depend on which row the query read first.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE collaborators SET accepted_at = now(), account_id = ? WHERE id = ?",
                        invitee,
                        second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // The grant set
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a capability outside the known set is refused, and a duplicate is not a stronger grant")
    void capabilitiesAreFromTheKnownSet() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(12));

        assertThatCode(() -> grant(id, "EDIT_STORY")).doesNotThrowAnyException();

        // A capability nobody implemented is a grant that silently authorises
        // nothing, which reads to a creator as the platform ignoring them.
        assertThatThrownBy(() -> grant(id, "EDIT_EVERYTHING"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Granting the same capability twice is not a stronger grant, and a duplicate
        // row would make a capability something that can be counted.
        assertThatThrownBy(() -> grant(id, "EDIT_STORY")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the enum and the check constraint agree, name for name")
    void everyCapabilityInTheEnumIsAcceptedByTheDatabase() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(13));

        // The one assertion that catches a capability added in Java and forgotten in
        // a migration: the failure would otherwise be a 500 on the first creator who
        // tried to grant it.
        for (Capability capability : Capability.values()) {
            assertThatCode(() -> grant(id, capability.name())).doesNotThrowAnyException();
        }
    }

    // -----------------------------------------------------------------------
    // Lifetimes
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deleting a campaign takes its collaborators and their capabilities with it")
    void collaboratorsCascadeFromTheProject() {
        UUID creator = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(14));
        grant(id, "EDIT_BASICS");

        jdbc().update("DELETE FROM projects WHERE id = ?", projectId);

        assertThat(count("SELECT count(*) FROM collaborators WHERE id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM collaborator_capabilities WHERE collaborator_id = ?", id))
                .isZero();
    }

    @Test
    @DisplayName("a collaborator's account cannot be hard-deleted out from under the grant")
    void grantsOutliveTheAccountsRow() {
        UUID creator = insertUser();
        UUID invitee = insertUser();
        UUID projectId = insertProject(creator);
        UUID id = insertInvitation(projectId, creator, "a@example.com", hash(15));
        jdbc().update("UPDATE collaborators SET accepted_at = now(), account_id = ? WHERE id = ?", invitee, id);

        // Deliberate, as with projects.creator_id: §17.4 closes an account by
        // anonymising the person in place, which leaves this reference pointing at a
        // row that no longer names anybody. A cascade would erase the record that
        // they ever had access.
        assertThatThrownBy(() -> jdbc().update("DELETE FROM users WHERE id = ?", invitee))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void grant(UUID collaboratorId, String capability) {
        jdbc().update(
                        "INSERT INTO collaborator_capabilities (collaborator_id, capability) VALUES (?, ?)",
                        collaboratorId,
                        capability);
    }

    private int count(String sql, UUID argument) {
        Integer count = jdbc().queryForObject(sql, Integer.class, argument);
        return count == null ? 0 : count;
    }
}
