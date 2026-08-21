package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.domain.RefreshToken;
import az.ideanest.auth.domain.Session;
import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import az.ideanest.auth.infrastructure.RefreshTokenRepository;
import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.VerificationTokenRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries the rest of the epic will be built on, run against PostgreSQL.
 *
 * <p>Transactional and therefore rolled back: these assert on query semantics,
 * not on what survives a commit.
 */
@Transactional
class IdentityRepositoryTests extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Autowired
    private UserRepository users;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private VerificationTokenRepository verificationTokens;

    @Autowired
    private EntityManager entityManager;

    private static byte[] hash(int seed) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    private User newUser(String email, String slug) {
        return users.saveAndFlush(User.register(EmailAddress.of(email), "Test Person", slug, "az", "AZN"));
    }

    @Test
    @DisplayName("a user is found by an email typed in any case")
    void findsUserByEmailIgnoringCase() {
        User saved = newUser("Person@Example.com", "person");

        assertThat(users.findByEmailAndDeletedAtIsNull(EmailAddress.of("PERSON@example.com")))
                .contains(saved);
    }

    @Test
    @DisplayName("a soft-deleted user is not found by any finder")
    void softDeletedUsersAreInvisible() {
        User saved = newUser("person@example.com", "person");
        users.saveAndFlush(saved);
        // Soft deletion is a column, so every finder has to exclude it. A
        // deleted account that can still be signed in to is the failure.
        entityManagerUpdateDeletedAt(saved.getId());

        assertThat(users.findByEmailAndDeletedAtIsNull(EmailAddress.of("person@example.com"))).isEmpty();
        assertThat(users.findByIdAndDeletedAtIsNull(saved.getId())).isEmpty();
        assertThat(users.findBySlugAndDeletedAtIsNull("person")).isEmpty();

        // Still present, and the address still taken: reusing it would let the
        // new owner receive a password reset for the old account's history.
        assertThat(users.findById(saved.getId())).isPresent();
        assertThat(users.existsByEmail(EmailAddress.of("person@example.com"))).isTrue();
    }

    private void entityManagerUpdateDeletedAt(UUID userId) {
        entityManager
                .createQuery("UPDATE User u SET u.deletedAt = :at WHERE u.id = :id")
                .setParameter("at", NOW)
                .setParameter("id", userId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    @DisplayName("the device list shows only sessions that could still be used")
    void liveSessionsExcludeRevokedAndExpired() {
        User user = newUser("person@example.com", "person");

        Session live = sessions.save(Session.start(user.getId(), NOW, NOW.plus(Duration.ofDays(30))));
        Session expired = sessions.save(Session.start(user.getId(), NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(10))));
        Session revoked = Session.start(user.getId(), NOW, NOW.plus(Duration.ofDays(30)));
        revoked.revoke(SessionRevocationReason.USER_REVOKED, NOW);
        sessions.saveAndFlush(revoked);
        sessions.flush();

        List<Session> found = sessions.findLiveByUser(user.getId(), NOW);

        // Showing a revoked or expired device invites the user to revoke
        // something that is already dead, and hides the one that is not.
        assertThat(found).containsExactly(live).doesNotContain(expired, revoked);
    }

    @Test
    @DisplayName("revoking every session for a user keeps the reason already recorded")
    void bulkRevocationDoesNotOverwriteAnEarlierReason() {
        User user = newUser("person@example.com", "person");
        Session alreadyRevoked = Session.start(user.getId(), NOW, NOW.plus(Duration.ofDays(30)));
        alreadyRevoked.revoke(SessionRevocationReason.TOKEN_REUSE, NOW);
        sessions.save(alreadyRevoked);
        Session live = sessions.save(Session.start(user.getId(), NOW, NOW.plus(Duration.ofDays(30))));
        sessions.flush();

        int affected = sessions.revokeAllForUser(user.getId(), SessionRevocationReason.PASSWORD_CHANGED, NOW.plusSeconds(60));

        assertThat(affected).isEqualTo(1);
        assertThat(sessions.findById(live.getId()))
                .get()
                .extracting(Session::getRevokedReason)
                .isEqualTo(SessionRevocationReason.PASSWORD_CHANGED);
        // The reason a session died during a theft is more informative than the
        // password change that followed it, and it is what an incident review
        // reads.
        assertThat(sessions.findById(alreadyRevoked.getId()))
                .get()
                .extracting(Session::getRevokedReason)
                .isEqualTo(SessionRevocationReason.TOKEN_REUSE);
    }

    @Test
    @DisplayName("a rotated refresh token is still found, which is how reuse is detected")
    void rotatedTokensRemainQueryable() {
        User user = newUser("person@example.com", "person");
        Session session = sessions.saveAndFlush(Session.start(user.getId(), NOW, NOW.plus(Duration.ofDays(30))));

        RefreshToken first = refreshTokens.save(
                RefreshToken.issue(session.getId(), hash(1), NOW, NOW.plus(Duration.ofDays(30))));
        RefreshToken second = refreshTokens.save(
                RefreshToken.issue(session.getId(), hash(60), NOW, NOW.plus(Duration.ofDays(30))));
        first.rotateInto(second, NOW.plusSeconds(1));
        refreshTokens.flush();

        assertThat(refreshTokens.findByTokenHash(hash(1)))
                .get()
                .extracting(RefreshToken::isUsed)
                .isEqualTo(true);
        assertThat(refreshTokens.findBySessionIdOrderByIssuedAtAscIdAsc(session.getId()))
                .containsExactly(first, second);
    }

    @Test
    @DisplayName("issuing a reset link retires the one before it")
    void outstandingVerificationTokensAreConsumed() {
        User user = newUser("person@example.com", "person");
        verificationTokens.save(VerificationToken.issue(
                user.getId(), VerificationPurpose.PASSWORD_RESET, hash(3), NOW, NOW.plus(Duration.ofHours(1))));
        verificationTokens.save(VerificationToken.issue(
                user.getId(), VerificationPurpose.EMAIL_VERIFICATION, hash(80), NOW, NOW.plus(Duration.ofHours(1))));
        verificationTokens.flush();

        int retired = verificationTokens.consumeOutstanding(
                user.getId(), VerificationPurpose.PASSWORD_RESET, NOW.plusSeconds(30));

        // Two live reset links double the window in which a forwarded email
        // still works, and the user only ever means to use the newest.
        assertThat(retired).isEqualTo(1);
        assertThat(verificationTokens.findByTokenHash(hash(3)))
                .get()
                .extracting(VerificationToken::isConsumed)
                .isEqualTo(true);
        // A different purpose is a different token. Verifying an address must
        // not silently cancel a reset the user has already started.
        assertThat(verificationTokens.findByTokenHash(hash(80)))
                .get()
                .extracting(VerificationToken::isConsumed)
                .isEqualTo(false);
    }
}
