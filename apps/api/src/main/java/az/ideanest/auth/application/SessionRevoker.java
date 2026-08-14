package az.ideanest.auth.application;

import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.infrastructure.SessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoking a session so that the revocation survives the failure that caused
 * it.
 *
 * <p>This exists as its own bean for one reason: {@code REQUIRES_NEW}. When a
 * refresh detects a reused token, the correct response is to kill the session
 * <em>and</em> refuse the request — and refusing means throwing, which rolls
 * back the transaction the refresh is running in. Revoking in the same
 * transaction would therefore undo itself, and the attacker's next attempt
 * would find a live session. A separate transaction commits the revocation
 * before the caller throws.
 *
 * <p>Being a separate bean is not stylistic either: a self-invocation would not
 * pass through the proxy and the propagation would be silently ignored.
 */
@Service
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final SessionRepository sessions;

    public SessionRevoker(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(UUID sessionId, SessionRevocationReason reason, Instant at) {
        sessions.findById(sessionId).ifPresent(session -> {
            session.revoke(reason, at);
            sessions.save(session);
        });

        if (reason == SessionRevocationReason.TOKEN_REUSE) {
            // Worth a line at this level. A rotated refresh token being
            // presented again means two parties hold one credential, and
            // whoever reads the logs afterwards needs to find this.
            log.warn("Refresh token reuse detected; session {} revoked.", sessionId);
        }
    }

    /**
     * Kills every live session a user has, in one statement.
     *
     * <p>Used when the password changes and after a theft. One statement rather
     * than a loop because a loop leaves a window in which some sessions are
     * dead and some are not, and whoever is holding a survivor uses exactly
     * that window.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllFor(UUID userId, SessionRevocationReason reason, Instant at) {
        return sessions.revokeAllForUser(userId, reason, at);
    }
}
