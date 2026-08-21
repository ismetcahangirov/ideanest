package az.ideanest.realtime.infrastructure;

import az.ideanest.realtime.RealtimeProperties;
import az.ideanest.realtime.application.RealtimeBroadcaster;
import az.ideanest.realtime.domain.RealtimeChannel;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The socket itself: one connection, one channel, and nothing the client can say.
 *
 * <h2>The channel is in the URL, and there is no subscribe frame</h2>
 *
 * <p>{@code /v1/realtime?channel=project:{id}}. The obvious alternative is a socket a client
 * subscribes on after connecting, which is what STOMP would give — and it would mean a protocol
 * on top of the protocol, a client library to speak it, and a set of frames to validate on an
 * unauthenticated endpoint. A page watches one campaign. Putting the channel in the handshake
 * makes the connection either valid or closed, decided once, before anything is registered.
 *
 * <p><strong>Inbound messages are ignored entirely.</strong> Not answered, not logged per
 * message, not parsed. There is nothing a viewer of a public campaign page can usefully tell the
 * server over a socket that the API does not already accept with a bearer token and a rate
 * limit, and a handler that parsed client frames would be an unauthenticated parser reachable by
 * anyone. Overriding {@link #handleTextMessage} to do nothing is the whole of the protection.
 *
 * <h2>What a client is not told</h2>
 *
 * <p>A close on an unknown channel, on a full process and on a full channel are all
 * {@link CloseStatus#NOT_ACCEPTABLE} with no reason string. A page that cannot open a socket
 * keeps the numbers it was served and stops trying, and the three cases call for the same
 * behaviour — so distinguishing them would only tell somebody probing the endpoint how close
 * they are to filling it.
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /** The query parameter carrying §12.1's channel name. */
    private static final String CHANNEL = "channel";

    /** Where the resolved channel is kept for the life of the session. */
    private static final String CHANNEL_ATTRIBUTE = "ideanest.realtime.channel";

    private final RealtimeBroadcaster broadcaster;
    private final RealtimeProperties properties;

    public RealtimeWebSocketHandler(RealtimeBroadcaster broadcaster, RealtimeProperties properties) {
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        RealtimeChannel channel = channelOf(session);
        if (channel == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        boolean registered = broadcaster.register(
                channel.name(), session, properties.maxSessions(), properties.maxSessionsPerChannel());
        if (!registered) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // Kept on the session so the close handler does not have to parse the URI again -- and,
        // more importantly, so that deregistration uses the name registration used even if the
        // parse ever changes.
        session.getAttributes().put(CHANNEL_ATTRIBUTE, channel.name());
        log.debug("A reader is watching {}", channel.name());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object channel = session.getAttributes().get(CHANNEL_ATTRIBUTE);
        if (channel instanceof String name) {
            broadcaster.deregister(name, session);
        }
    }

    /**
     * Ignored, deliberately and completely. See the class comment.
     *
     * <p>Not even a log line: a client sending a message per second would otherwise be a client
     * writing to the platform's logs at whatever rate it chose.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Nothing.
    }

    /**
     * A transport error closes the session rather than being retried.
     *
     * <p>Spring's default logs and closes; this additionally deregisters, so the broadcaster's
     * map does not hold a session the container is about to discard. Debug rather than warn,
     * because a dropped connection is what a mobile network does.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("A live subscriber's connection failed", exception);
        afterConnectionClosed(session, CloseStatus.SESSION_NOT_RELIABLE);
        if (session.isOpen()) {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    /** The channel this session asked for, or null when it asked for something unserved. */
    private static RealtimeChannel channelOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }

        for (String parameter : uri.getQuery().split("&")) {
            int equals = parameter.indexOf('=');
            if (equals > 0 && CHANNEL.equals(parameter.substring(0, equals))) {
                // Decoded because a channel name contains a colon, which a client may
                // percent-encode and which two clients will disagree about.
                return RealtimeChannel.parse(
                        URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return null;
    }
}
