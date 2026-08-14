package az.ideanest.auth.application;

import az.ideanest.auth.domain.Session;
import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.infrastructure.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The device list, and revoking from it.
 *
 * <p>This is what a user reaches for after losing a laptop, and it is only
 * useful if it is honest: it shows what could still be used, and revoking from
 * it takes effect.
 *
 * <p>Ownership is checked here rather than in the controller. §17.3 lists
 * insecure direct object reference as a threat and puts the control in a
 * security layer for a reason — a check that lives in a controller is a check
 * that the next controller forgets.
 */
@Service
public class SessionDirectory {

    private final SessionRepository sessions;
    private final SessionRevoker revoker;
    private final Clock clock;

    public SessionDirectory(SessionRepository sessions, SessionRevoker revoker, Clock clock) {
        this.sessions = sessions;
        this.revoker = revoker;
        this.clock = clock;
    }

    /**
     * @param current whether this is the session the request was made from.
     *     Without it the list is a row of near-identical devices and the user
     *     cannot tell which one revoking will sign them out of
     */
    public record SessionSummary(
            UUID id,
            String deviceLabel,
            String userAgent,
            String ipAddress,
            Instant createdAt,
            Instant lastSeenAt,
            Instant expiresAt,
            boolean current) {
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> listFor(UUID userId, UUID currentSessionId) {
        return sessions.findLiveByUser(userId, clock.instant()).stream()
                .map(session -> toSummary(session, currentSessionId))
                .toList();
    }

    /**
     * Revokes one session belonging to {@code userId}.
     *
     * @return false when the session does not exist <em>or</em> belongs to
     *     somebody else. The caller answers 404 to both, because distinguishing
     *     them turns this endpoint into a way of asking whether a given
     *     identifier is a real session
     */
    @Transactional
    public boolean revoke(UUID userId, UUID sessionId) {
        Optional<Session> session = sessions.findById(sessionId);
        if (session.isEmpty() || !session.get().getUserId().equals(userId)) {
            return false;
        }
        // Revoking one that is already revoked is not an error. The user asked
        // for it to be dead and it is; the first reason recorded stays.
        revoker.revoke(sessionId, SessionRevocationReason.USER_REVOKED, clock.instant());
        return true;
    }

    private static SessionSummary toSummary(Session session, UUID currentSessionId) {
        return new SessionSummary(
                session.getId(),
                session.getDeviceLabel(),
                session.getUserAgent(),
                session.getIpAddress(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                session.getId().equals(currentSessionId));
    }
}
