package az.ideanest.realtime;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §12.1's settings: how often a page is told, and how much of the process it may occupy.
 *
 * <p>Every value has a default that a deployment configuring nothing still gets, for
 * {@code CommunityProperties}' reason: binding leaves an omitted property at its zero value, and
 * a window of zero is a broadcast per event — which is the behaviour this module exists to stop.
 *
 * @param enabled whether the socket is served at all. <strong>A switch rather than a
 *     feature flag</strong>: this module is the one part of the platform whose absence changes no
 *     stored fact, so turning it off during an incident costs live counters and nothing else.
 *     <p><strong>Boxed, and that is load-bearing rather than a style choice.</strong> Binding
 *     leaves an omitted property at its zero value, and the zero value of a primitive
 *     {@code boolean} is {@code false} — so a deployment that configures nothing would get the
 *     module switched off, silently, with no way for this record to tell "absent" from
 *     "deliberately off". Every other default in this file is a number or a string, where zero
 *     and null are values nobody would set on purpose; this is the one where the safe default is
 *     the opposite of the zero
 * @param flushSchedule how often a window is closed and its message sent, as a UTC cron
 *     expression — the same vocabulary §8.4's jobs use, including {@code -} for "do not schedule
 *     this". §12.1 says one second and {@code * * * * * *} is the default. <strong>The whole
 *     point of the module is this number</strong>: a campaign taking forty pledges a second is
 *     forty frames per viewer per second without it, and the counter is unreadable at that rate
 *     anyway.
 *     <p><strong>A schedule rather than a {@code Duration}, and {@code -} rather than
 *     {@code enabled: false}, so that the test profile can stop the timer without stopping the
 *     accumulation</strong> — a suite that switched the module off would be asserting on a
 *     buffer nothing ever filled. It is the arrangement every job on the platform already uses
 *     and for the same reason
 * @param maxSessions how many sockets this process will hold at once, across every channel.
 *     A bound rather than a target: each one is a thread-safe entry in a map and an open
 *     connection, and an unauthenticated endpoint with no ceiling is an invitation. Refused at
 *     the handshake, so a client that cannot get in falls back to the page it already has
 * @param maxSessionsPerChannel how many one campaign may hold. Separate from the total because
 *     the failure it prevents is different: without it, one popular campaign fills the process
 *     and every other campaign's readers are refused
 * @param allowedOrigins which origins may open a socket.
 *     <p><strong>This is the first browser-facing endpoint on the service, and that is why the
 *     property exists here rather than beside a CORS configuration.</strong> There is no CORS
 *     configuration: every other route is called by the web application's <em>server</em>, so no
 *     browser has ever had to be allowed. A socket is opened by the page itself, from the web
 *     application's origin to the API's.
 *     <p>It matters more than the usual CORS argument does, because <strong>a WebSocket
 *     handshake is not subject to CORS at all</strong>: a browser will let any page on any
 *     origin open one unless the server checks {@code Origin} itself. Empty is the safe default
 *     — Spring then allows the same origin only, which in practice is nobody, and a deployment
 *     that has not named its web application has no reader to serve
 */
@ConfigurationProperties(prefix = "ideanest.realtime")
public record RealtimeProperties(
        Boolean enabled,
        String flushSchedule,
        int maxSessions,
        int maxSessionsPerChannel,
        List<String> allowedOrigins) {

    /** Every second, which is §12.1's window. */
    private static final String DEFAULT_SCHEDULE = "* * * * * *";

    private static final int DEFAULT_MAX_SESSIONS = 10_000;

    private static final int DEFAULT_MAX_SESSIONS_PER_CHANNEL = 2_000;

    public static RealtimeProperties defaults() {
        return new RealtimeProperties(
                true, DEFAULT_SCHEDULE, DEFAULT_MAX_SESSIONS, DEFAULT_MAX_SESSIONS_PER_CHANNEL, List.of());
    }

    public RealtimeProperties {
        enabled = enabled == null || enabled;
        flushSchedule = flushSchedule == null || flushSchedule.isBlank() ? DEFAULT_SCHEDULE : flushSchedule;
        maxSessions = maxSessions == 0 ? DEFAULT_MAX_SESSIONS : maxSessions;
        maxSessionsPerChannel = maxSessionsPerChannel == 0 ? DEFAULT_MAX_SESSIONS_PER_CHANNEL : maxSessionsPerChannel;
        // Empty rather than null, and empty is the safe default rather than the permissive one:
        // Spring falls back to same-origin, which for a separately hosted API is nobody.
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);

        if (maxSessions < 1 || maxSessionsPerChannel < 1) {
            throw new IllegalArgumentException("A socket limit of zero is the feature switched off, which is `enabled`");
        }
        if (maxSessionsPerChannel > maxSessions) {
            // Otherwise the per-channel limit can never be reached and reads as though it
            // bounded something. A start-up problem wearing a runtime costume.
            throw new IllegalArgumentException("One channel cannot hold more sessions than the process does");
        }
    }
}
