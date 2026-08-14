package az.ideanest.auth.application;

import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.TwoFactorSecretRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What another module asks about somebody's second factor.
 *
 * <p>§17.1 requires two-factor for payout actions, and this is the door that
 * requirement comes through: the payout module calls
 * {@link #isProvedBy(UUID)} with the session on the request rather than reading
 * the auth module's tables, which is the boundary {@code ModuleBoundaryTests}
 * enforces.
 *
 * <p><strong>The question is about the session, not the account.</strong>
 * "Does this person have two-factor enabled" is the wrong check for a payout:
 * an account can have it switched on and still hold sessions that predate it,
 * and a token minted from one of those proves only a password. The right
 * question is what this sign-in proved, which is what the {@code amr} claim
 * carries and what this reads from the row behind it.
 *
 * <p>A resource server can and should read the claim instead of calling this —
 * it is signed, and it costs no query. This exists for the decisions that
 * cannot: a background job acting on a session, and any check that must be
 * right after a session was revoked rather than fifteen minutes later.
 */
@Service
public class TwoFactorPolicy {

    private final TwoFactorSecretRepository secrets;
    private final SessionRepository sessions;

    public TwoFactorPolicy(TwoFactorSecretRepository secrets, SessionRepository sessions) {
        this.secrets = secrets;
        this.sessions = sessions;
    }

    /**
     * Whether the account has a confirmed second factor.
     *
     * <p>For telling a creator that they have to set one up before they can be
     * paid — not for deciding whether a request may proceed. See the class
     * comment.
     */
    @Transactional(readOnly = true)
    public boolean isEnabledFor(UUID userId) {
        return secrets.findByUserIdAndConfirmedAtIsNotNull(userId).isPresent();
    }

    /** Whether this session proved a second factor when it was created, or shortly after. */
    @Transactional(readOnly = true)
    public boolean isProvedBy(UUID sessionId) {
        return sessions.findById(sessionId)
                .filter(session -> session.isTwoFactorAuthenticated())
                .isPresent();
    }
}
