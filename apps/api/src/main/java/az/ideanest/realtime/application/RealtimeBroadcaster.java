package az.ideanest.realtime.application;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Who is listening to what, and pushing one message to all of them.
 *
 * <h2>One replica, stated rather than implied</h2>
 *
 * <p>§12.1 says "scaling uses a Redis-backed pub/sub adapter", and there is no Redis. So this
 * pushes to the sessions <em>this process</em> holds, and on N replicas a reader is told about
 * roughly one event in N.
 *
 * <p><strong>That is a degraded counter and not a wrong page</strong>, which is the only reason
 * it is acceptable to ship: the numbers in the server-rendered document are correct, they refresh
 * on navigation, and nothing on the platform reads state from this module. The relay is a
 * dependency and a configuration surface, and adding one to make a counter smoother — before any
 * production telemetry says the counter is a problem — is the shape of decision §13's Tier 2
 * rule exists to refuse. What the follow-up is is exactly this class: a publish to Redis instead
 * of a loop, and a subscriber that calls the loop.
 *
 * <h2>Bounded, because the endpoint needs no credential</h2>
 *
 * <p>{@link #register} refuses past {@code max-sessions} and past
 * {@code max-sessions-per-channel}, and the two bound different failures: the first is the
 * process, the second is one popular campaign filling it and refusing everybody else's readers.
 * A refused socket is a page that keeps the numbers it was served, which is why refusing is
 * acceptable at all.
 *
 * <h2>A dead session is removed here, not waited for</h2>
 *
 * <p>A send to a socket the client has abandoned throws, and the alternative to removing it on
 * the spot is a map that grows until the container's idle timeout notices. So a failed send
 * deregisters, and it does so at {@code debug}: a reader closing a tab mid-broadcast is the
 * ordinary case, not a fault.
 */
@Component
public class RealtimeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcaster.class);

    /**
     * Channel name to the sessions on it.
     *
     * <p>A {@link ConcurrentHashMap} of concurrent sets, because both halves are written from
     * two directions at once: handshakes and closes arrive on container threads, and the flush
     * iterates on its own. A synchronised map would make every viewer's connect wait behind the
     * broadcast to a channel they are not on.
     */
    private final Map<String, Set<WebSocketSession>> channels = new ConcurrentHashMap<>();

    /**
     * Registers a session on a channel.
     *
     * @return false when a bound refused it, in which case the caller closes the socket. Not an
     *     exception: being full is an ordinary state of a public endpoint, and a stack trace per
     *     refused reader on a busy campaign is a log nobody can use
     */
    public boolean register(String channel, WebSocketSession session, int maxSessions, int maxPerChannel) {
        if (total() >= maxSessions) {
            log.warn("Refusing a live subscription to {}: this process is holding {} sockets", channel, maxSessions);
            return false;
        }

        Set<WebSocketSession> sessions =
                channels.computeIfAbsent(channel, name -> ConcurrentHashMap.newKeySet());
        if (sessions.size() >= maxPerChannel) {
            log.warn("Refusing a live subscription to {}: it is already holding {} sockets", channel, maxPerChannel);
            return false;
        }

        sessions.add(session);
        return true;
    }

    /**
     * Forgets a session.
     *
     * <p>The channel's entry goes when its last session does, so a campaign nobody is watching
     * costs nothing — a map that kept an empty set per campaign ever viewed would be a slow leak
     * with a plausible explanation.
     */
    public void deregister(String channel, WebSocketSession session) {
        channels.computeIfPresent(channel, (name, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Pushes one message to everybody on a channel.
     *
     * @return how many sessions received it, which is what the flush job logs
     */
    public int broadcast(String channel, String payload) {
        Set<WebSocketSession> sessions = channels.get(channel);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }

        TextMessage message = new TextMessage(payload);
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                deregister(channel, session);
                continue;
            }
            try {
                // Synchronized on the session: Spring's WebSocketSession is explicitly not safe
                // for concurrent sends, and a second broadcast to the same reader -- they may be
                // on both a campaign's channels -- would otherwise interleave two frames.
                synchronized (session) {
                    session.sendMessage(message);
                }
                delivered++;
            } catch (IOException | IllegalStateException gone) {
                // The ordinary end of a socket: a tab closed mid-broadcast. Debug rather than
                // warn, and deregistered here so the map does not wait for a timeout.
                log.debug("A live subscriber to {} went away mid-broadcast", channel, gone);
                deregister(channel, session);
            }
        }
        return delivered;
    }

    /** How many sockets this process holds, across every channel. */
    public int total() {
        return channels.values().stream().mapToInt(Set::size).sum();
    }

    /** How many sockets are on one channel. */
    public int subscribers(String channel) {
        Set<WebSocketSession> sessions = channels.get(channel);
        return sessions == null ? 0 : sessions.size();
    }
}
