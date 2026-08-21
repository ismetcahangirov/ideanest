package az.ideanest.realtime.application;

import az.ideanest.realtime.RealtimeProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The tick that ends a window: takes what has accumulated and pushes one message per channel.
 *
 * <h2>Why this is not a {@code ScheduledJob}, and why that is not a loophole</h2>
 *
 * <p>Everything on a timer in this platform implements {@code ScheduledJob} and claims a lease
 * first — #134's whole point, and {@code JobTriggerTests} enforces the absence of
 * {@code @Scheduled} so there is no second door. This is the one piece of work that must run on
 * <em>every</em> replica, and the lease is precisely what would stop it: the buffer it drains
 * and the sockets it writes to are this process's, held in this process's memory, so a lease
 * would elect one replica to flush its own windows while the others accumulated forever.
 *
 * <p>So it is scheduled directly against the same {@code TaskScheduler} {@code JobScheduler}
 * uses, which is the mechanism rather than the annotation the test bans — and the reason the
 * test bans the annotation does not apply here. There is nothing to claim: two replicas
 * flushing at the same moment are two replicas each telling their own readers, which is the
 * intended behaviour and not a duplicate.
 *
 * <p><strong>Registered after the application is ready</strong>, for {@code JobScheduler}'s
 * reason: a tick that fired during the refresh would run against a half-built context.
 *
 * <h2>A failed flush costs one window</h2>
 *
 * <p>Everything here is caught. A tick that threw would be a tick the {@code TaskScheduler}
 * stops repeating — a scheduled task that raises an exception is not rescheduled — so the whole
 * feature would go quiet after one bad message, on one replica, with nothing but a log line to
 * say so. Since the window has already been drained, what a failure costs is that window and
 * nothing more.
 */
@Component
public class RealtimeFlusher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeFlusher.class);

    /** The platform's vocabulary for "do not schedule this", shared with §8.4's jobs. */
    private static final String DISABLED = "-";

    private final RealtimeAggregator aggregator;
    private final RealtimeBroadcaster broadcaster;
    private final RealtimeProperties properties;
    private final TaskScheduler timers;
    private final ObjectMapper json;

    public RealtimeFlusher(
            RealtimeAggregator aggregator,
            RealtimeBroadcaster broadcaster,
            RealtimeProperties properties,
            TaskScheduler timers,
            ObjectMapper json) {

        this.aggregator = aggregator;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.timers = timers;
        this.json = json;
    }

    /**
     * Starts the tick, unless the module is switched off.
     *
     * <p>The test profile sets the schedule to {@code -} and calls {@link #flush()} directly,
     * exactly as every §8.4 job's schedule is disabled there: a timer firing in the background of
     * a suite pushes the very window a test is about to assert on. It stops the <em>timer</em>
     * and not the accumulation, which is why the module is not simply switched off there —
     * a suite asserting on a buffer nothing ever filled would assert nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startFlushing() {
        if (!properties.enabled() || DISABLED.equals(properties.flushSchedule())) {
            log.info("Live updates are not being flushed on a timer");
            return;
        }
        timers.schedule(this::flush, new CronTrigger(properties.flushSchedule()));
        log.info("Live update windows close on {}", properties.flushSchedule());
    }

    /**
     * One tick: drain, serialise, push.
     *
     * @return how many channels had something to say, which is what a test asserts on and what
     *     makes an idle platform's log silent rather than one line a second
     */
    public int flush() {
        List<ChannelWindow> windows;
        try {
            windows = aggregator.drain();
        } catch (RuntimeException e) {
            // See the class comment: a throw here would cancel the timer.
            log.error("Could not drain the live-update windows; this tick is lost", e);
            return 0;
        }

        int spoken = 0;
        for (ChannelWindow window : windows) {
            try {
                int delivered = broadcaster.broadcast(window.channel(), payloadOf(window));
                if (delivered > 0) {
                    spoken++;
                }
            } catch (RuntimeException e) {
                // One channel per iteration, and one failure must not silence the rest.
                log.error("Could not broadcast the live update for {}; this window is lost", window.channel(), e);
            }
        }
        return spoken;
    }

    /**
     * The wire format: a small JSON object, written by the application's own {@code ObjectMapper}.
     *
     * <p>Which is what makes the amount a string rather than a JSON number, without this class
     * having to know that is what §10.3 asks for — the same argument {@code NotificationFanOut}
     * makes about a rendering document. It matters more here than there: this value is added to
     * a running total in a browser.
     *
     * <p><strong>Keys are omitted rather than sent as zero or null.</strong> A comments message
     * has no amount and a counter message has no comment identifier, and a client that had to
     * distinguish "no pledges this window" from "zero money this window" would be a client with
     * a rule to get wrong.
     */
    private String payloadOf(ChannelWindow window) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", window.channel());
        if (window.pledges() > 0) {
            payload.put("pledges", window.pledges());
            if (window.amount() != null) {
                payload.put("amount", window.amount());
            }
        }
        if (window.comments() > 0) {
            payload.put("comments", window.comments());
            if (window.latestCommentId() != null) {
                payload.put("latestCommentId", window.latestCommentId());
            }
        }

        try {
            return json.writeValueAsString(payload);
        } catch (JacksonException unserialisable) {
            // Unchecked in Jackson 3, and caught for the reason above: a databind error thrown
            // out of the tick would cancel the timer for the whole process.
            throw new IllegalStateException("A live update for " + window.channel() + " could not be written",
                    unserialisable);
        }
    }
}
