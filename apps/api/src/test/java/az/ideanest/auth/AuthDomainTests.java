package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.auth.domain.RefreshToken;
import az.ideanest.auth.domain.Session;
import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import az.ideanest.shared.Identifiers;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that hold before anything is stored.
 *
 * <p>No database: these are decisions about state transitions, and they are
 * worth being able to run in milliseconds.
 */
class AuthDomainTests {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private static byte[] hash(int seed) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i);
        }
        return value;
    }

    // -----------------------------------------------------------------------
    // Session
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a session is live until it is revoked or expires")
    void sessionLiveness() {
        Session session = Session.start(UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(30)));

        assertThat(session.isLive(NOW)).isTrue();
        assertThat(session.isLive(NOW.plus(Duration.ofDays(31)))).isFalse();

        session.revoke(SessionRevocationReason.SIGNED_OUT, NOW);
        assertThat(session.isLive(NOW)).isFalse();
    }

    @Test
    @DisplayName("expiry is exclusive: a session is dead at the instant it expires")
    void expiryIsExclusive() {
        Instant expiry = NOW.plus(Duration.ofDays(30));
        Session session = Session.start(UUID.randomUUID(), NOW, expiry);

        // An off-by-one here is a token that works for one more request than it
        // should, which is the kind of thing nobody notices until an audit.
        assertThat(session.isExpired(expiry)).isTrue();
        assertThat(session.isExpired(expiry.minusMillis(1))).isFalse();
    }

    @Test
    @DisplayName("the first revocation reason is the one that is kept")
    void revocationIsNotOverwritten() {
        Session session = Session.start(UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(30)));

        session.revoke(SessionRevocationReason.TOKEN_REUSE, NOW);
        session.revoke(SessionRevocationReason.SIGNED_OUT, NOW.plusSeconds(1));

        // Two concurrent requests can both detect reuse, and a later sign-out
        // must not rewrite the record into something benign. The theft is the
        // fact worth keeping.
        assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
        assertThat(session.getRevokedAt()).isEqualTo(NOW);
    }

    // -----------------------------------------------------------------------
    // RefreshToken
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a refresh token hash must be a SHA-256")
    void refreshTokenRejectsWrongHashLength() {
        assertThatThrownBy(() ->
                        RefreshToken.issue(UUID.randomUUID(), new byte[16], NOW, NOW.plus(Duration.ofDays(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    @DisplayName("rotating a token twice is refused, because that is the theft signal")
    void refreshTokenCannotRotateTwice() {
        UUID sessionId = Identifiers.newIdentifier();
        RefreshToken first = RefreshToken.issue(sessionId, hash(1), NOW, NOW.plus(Duration.ofDays(30)));
        RefreshToken second = RefreshToken.issue(sessionId, hash(50), NOW, NOW.plus(Duration.ofDays(30)));
        RefreshToken third = RefreshToken.issue(sessionId, hash(100), NOW, NOW.plus(Duration.ofDays(30)));

        first.rotateInto(second, NOW);

        assertThat(first.isUsed()).isTrue();
        assertThat(first.getReplacedBy()).isEqualTo(second.getId());

        // Silently overwriting the first rotation would erase the evidence that
        // one token was exchanged twice — which is the whole detection.
        assertThatThrownBy(() -> first.rotateInto(third, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already rotated");
    }

    @Test
    @DisplayName("the stored hash cannot be mutated through the accessor")
    void refreshTokenHashIsCopiedOut() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), hash(1), NOW, NOW.plus(Duration.ofDays(30)));

        byte[] borrowed = token.getTokenHash();
        borrowed[0] = 0;

        // Arrays are shared references. Handing out the live one lets a caller
        // change the lookup key of a persistent entity by accident.
        assertThat(token.getTokenHash()).isEqualTo(hash(1));
    }

    // -----------------------------------------------------------------------
    // VerificationToken
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a verification token is redeemable once")
    void verificationTokenIsSingleUse() {
        VerificationToken token = VerificationToken.issue(
                UUID.randomUUID(), VerificationPurpose.PASSWORD_RESET, hash(9), NOW, NOW.plus(Duration.ofHours(1)));

        assertThat(token.isRedeemable(NOW)).isTrue();
        token.consume(NOW);
        assertThat(token.isRedeemable(NOW)).isFalse();

        // A reset link that keeps working is a standing key to the account, and
        // it sits in a mailbox for as long as the mailbox exists.
        assertThatThrownBy(() -> token.consume(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }

    @Test
    @DisplayName("an expired verification token is not redeemable")
    void verificationTokenExpires() {
        VerificationToken token = VerificationToken.issue(
                UUID.randomUUID(), VerificationPurpose.EMAIL_VERIFICATION, hash(9), NOW, NOW.plus(Duration.ofHours(1)));

        assertThat(token.isRedeemable(NOW.plus(Duration.ofHours(2)))).isFalse();
    }
}
